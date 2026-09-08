package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentStatus;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 취소의 DB 쓰기만 맡는다. <b>point3 호출은 한 줄도 들어오지 않는다</b> (CLAUDE.md 규칙 1).
 *
 * {@link RefundService} 가 오케스트레이션하고 여기는 짧은 트랜잭션 여러 개로 끊어 담는다.
 * 특히 {@link #prepare} 는 요청을 보내기 <em>전에</em> 커밋돼야 한다 —
 * Idempotency-Key 를 남겨야 타임아웃 났을 때 무엇으로 보냈는지 알고 조회할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundWriter {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final RefundRepository refundRepository;
	private final PaymentRepository paymentRepository;
	private final OrderRepository orderRepository;
	private final SaleFormRepository saleFormRepository;
	private final Clock clock;

	/**
	 * 취소 요청을 준비한다 — 금액·세금 계산 + refund 행 생성 + 키 발급.
	 *
	 * <b>진행 중인 취소가 있으면 만들지 않는다.</b> 취소는 멱등하지 않아 겹치면 두 번 환불된다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Prepared prepare(Long paymentId, Long orderId, int requestedAmount,
	                        String reason, RefundRequester requester) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		if (payment.getStatus() != PaymentStatus.CAPTURED) {
			throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED, "결제가 완료된 주문만 취소할 수 있습니다.");
		}
		if (payment.getSessionId() == null) {
			throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED);
		}
		if (refundRepository.existsByPaymentIdAndStatus(paymentId, RefundStatus.PROCESSING)) {
			// 겹쳐 보내면 두 번 환불된다. 진행 중인 건이 끝나야 다음을 받는다
			throw new BusinessException(ErrorCode.REFUND_IN_PROGRESS);
		}

		RefundedTotals refunded = refundRepository.sumCompleted(paymentId);
		RefundTax tax = RefundTaxCalculator.split(
				requestedAmount,
				payment.getAmount(), nullToZero(payment.getVat()), payment.getTaxFreeAmount(),
				refunded.amountInt(), refunded.vatInt(), refunded.taxFreeInt());

		Refund refund = refundRepository.saveAndFlush(
				Refund.request(paymentId, orderId, newIdempotencyKey(), tax, reason, requester));

		log.info("취소 요청 준비: refundId={}, paymentId={}, amount={}", refund.getId(), paymentId, tax.amount());
		return new Prepared(refund.getId(), payment.getSessionId(), tax, refund.getIdempotencyKey());
	}

	/**
	 * 환불 확정. <b>멱등하다</b> — 실시간 처리와 대사 배치가 같은 건을 확정할 수 있다.
	 *
	 * 재고 되돌림도 여기서 한다 (D-024). 두 번 확정되면 재고가 두 번 돌아가므로
	 * 멱등 가드 안쪽에 둔다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean complete(Long refundId, String point3RefundId) {
		Refund refund = refundRepository.findByIdForUpdate(refundId)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

		if (!refund.markCompleted(point3RefundId)) {
			return false;
		}

		Payment payment = paymentRepository.findById(refund.getPaymentId())
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
		if (payment.getPhase() == PaymentPhase.SECOND) {
			// 하나의 주문 취소가 1차금·2차금 두 건의 환불로 나간다. 여기서 또 정리하면
			// 재고가 두 번 돌아간다 — 재고와 주문 상태는 1차금 쪽에서만 건드린다
			return true;
		}

		List<Order> targets = targetsOf(refund, payment);
		LocalDateTime now = LocalDateTime.now(clock);
		targets.forEach(this::restoreOne);
		targets.forEach(order -> order.cancel(now));
		payment.getOrderGroup().cancelIfAllOrdersCanceled(now);
		return true;
	}

	/**
	 * 실패 확정. <b>확인된 실패에만 부른다</b> — 확정 거절 409 · 422 · 조회로 확인한 failed.
	 * 타임아웃·5xx·미확정 409 로는 부르지 않는다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void fail(Long refundId, String reason) {
		refundRepository.findByIdForUpdate(refundId)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND))
				.markFailed(reason);
	}

	/** 정산이 끝나 시스템 취소가 불가능하다. 셀러 직접 환불로 넘긴다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markSettledManual(Long refundId) {
		refundRepository.findByIdForUpdate(refundId)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND))
				.markSettledManual();
	}

	/** 미확정 409 응답에 실려 온 항목 id 를 붙여 둔다. 조회 때 우리 건을 집어내는 데 쓴다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void attachPoint3Id(Long refundId, String point3RefundId) {
		refundRepository.findById(refundId).ifPresent(r -> r.attachPoint3Id(point3RefundId));
	}

	/** 대사 배치가 처리할 미확정 건. 자기호출이면 프록시를 안 타므로 여기 둔다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Long> claimPending(LocalDateTime threshold, int limit) {
		return refundRepository.findPendingForUpdate(threshold, limit)
				.stream().map(Refund::getId).toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public RefundSnapshot snapshot(Long refundId) {
		Refund refund = refundRepository.findById(refundId)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
		Payment payment = paymentRepository.findById(refund.getPaymentId())
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		return new RefundSnapshot(refund.getId(), payment.getSessionId(),
				refund.getPoint3RefundId(), refund.getAmount());
	}

	// ---------------------------------------------------------------- 재고

	/**
	 * 이번 취소로 되돌아갈 주문들. 재고 되돌림과 주문 취소 표시가 여기에 걸린다 (D-024).
	 *
	 * <pre>
	 *   SOLO              → 되돌린다. 창고에 실물이 돌아온다
	 *   GROUP · 마감 전   → 되돌린다. 발주 전이라 안전하고, 그 자리를 다른 사람이 채울 수 있다
	 *   GROUP · 마감 후   → 두 번 다시 건드리지 않는다. 셀러가 이미 발주했다
	 * </pre>
	 *
	 * 기준은 판매 유형이 아니라 <b>발주가 나갔는가</b> 이고, GROUP 에서는 그게 마감 시점과 같다.
	 */
	private List<Order> targetsOf(Refund refund, Payment payment) {
		if (refund.getOrderId() == null) {
			// 전액 취소 — 아직 살아 있는 주문 전부가 대상이다
			return payment.getOrderGroup().activeOrders();
		}
		return orderRepository.findById(refund.getOrderId())
				.filter(order -> !order.isCanceled())
				.map(List::of)
				.orElseGet(List::of);
	}

	private void restoreOne(Order order) {
		SaleForm form = order.getSaleForm();
		if (!isRestorable(form)) {
			log.info("재고를 되돌리지 않는다 (발주 완료): saleFormId={}, qty={}", form.getId(), order.getQty());
			return;
		}
		int affected = saleFormRepository.restoreSold(form.getId(), order.getQty());
		if (affected == 0) {
			// sold 가 그만큼 없다. 데이터가 어긋난 상태라 조용히 넘기지 않는다
			log.error("재고 되돌리기 실패: saleFormId={}, qty={}", form.getId(), order.getQty());
		}
	}

	private static boolean isRestorable(SaleForm form) {
		if (form.getSaleType() == SaleType.SOLO) {
			return true;
		}
		// GROUP 은 마감 전에만. 마감 후에는 셀러가 이미 그 수량으로 발주했다
		return form.getStatus() == SaleFormStatus.SELLING || form.getStatus() == SaleFormStatus.PAUSED;
	}

	private static int nullToZero(Integer value) {
		return value == null ? 0 : value;
	}

	/**
	 * 하나의 논리적 취소에 하나만 만든다. 재시도한다고 새로 만들면 이중 환불이다.
	 * 24시간 동안 유효하므로 그 안에 충돌하지 않을 만큼 넓게 뽑는다.
	 */
	private static String newIdempotencyKey() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return "rf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// ---------------------------------------------------------------- 반환 타입

	public record Prepared(Long refundId, String sessionId, RefundTax tax, String idempotencyKey) {
	}

	public record RefundSnapshot(Long refundId, String sessionId, String point3RefundId, int amount) {
	}
}
