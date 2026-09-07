package store.moeum.moeum.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentActor;
import store.moeum.moeum.payment.domain.PaymentEvent;
import store.moeum.moeum.payment.domain.PaymentEventRepository;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.domain.PaymentStatus;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.payment.infra.Point3Session;
import store.moeum.moeum.saleform.domain.SaleFormRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제의 DB 쓰기만 맡는다. <b>point3 호출은 한 줄도 들어오지 않는다.</b>
 *
 * {@link PaymentService} 와 나눈 이유가 트랜잭션 경계다 (CLAUDE.md 규칙 1).
 * 외부 호출을 트랜잭션 안에 두면 point3 가 30초 걸릴 때 DB 락을 30초 잡는다.
 * 오케스트레이션은 저쪽이 하고, 여기는 짧은 트랜잭션 여러 개로 끊어 담는다.
 *
 * {@code REQUIRES_NEW} 를 쓰는 이유는 호출자가 트랜잭션 없이 부르기 때문이다 —
 * 각 메서드가 자기 트랜잭션을 열고 닫아야 point3 호출 사이사이에 확실히 커밋된다.
 * 특히 {@code CAPTURE_PENDING} 은 승인 호출 <em>전에</em> 커밋돼야 의미가 있다 (D-004).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWriter {

	private final PaymentRepository paymentRepository;
	private final PaymentEventRepository paymentEventRepository;
	private final OrderGroupRepository orderGroupRepository;
	private final StockHoldRepository stockHoldRepository;
	private final SaleFormRepository saleFormRepository;

	/**
	 * 세션 생성 직전 준비 (payment-flow 9번).
	 *
	 * 홀드가 살아 있고 공구가 안 닫혔는지 확인한 뒤 payment 행을 마련한다.
	 * 이미 실패한 행이 있으면 재사용한다 (D-023) — 유니크 때문에 새로 만들 수 없다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Prepared prepareFirst(String kakaoId, String sessionToken) {
		OrderGroup group = orderGroupRepository.findBySessionToken(sessionToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		requireOwner(group, kakaoId);
		if (group.isPaid()) {
			// 중복 진입. 결제창을 또 띄우면 두 번 결제된다
			throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS, "이미 결제가 완료된 주문입니다.");
		}
		requireHoldsAlive(group);

		int amount = group.firstPaymentAmount();
		Payment payment = paymentRepository
				.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.FIRST)
				.orElse(null);

		if (payment == null) {
			payment = paymentRepository.saveAndFlush(
					Payment.create(group, PaymentPhase.FIRST, amount));
			record(payment, null, PaymentStatus.CREATED, "결제 세션 준비", PaymentActor.USER);
		} else if (payment.getStatus().isPending()) {
			// 승인 결과를 모르는 건이 있다. 새 세션을 열면 이중 결제가 된다
			throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
		} else if (payment.getStatus() == PaymentStatus.CAPTURED) {
			throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS, "이미 결제가 완료된 주문입니다.");
		} else if (payment.getStatus().isReusable()) {
			PaymentStatus before = payment.getStatus();
			payment.resetForRetry(amount);
			record(payment, before, PaymentStatus.CREATED, "재결제 시도", PaymentActor.USER);
		}

		return new Prepared(payment.getId(), group.getId(), amount, productNameOf(group));
	}

	/**
	 * point3 세션을 붙이고 결제창으로 보낼 준비를 마친다 (payment-flow 12번).
	 *
	 * <b>여기서 저장에 실패하면 sessionId 를 잃는다.</b> point3 세션 생성 요청에는
	 * 가맹점 주문번호를 넣을 필드가 없어서, 어느 주문의 세션인지 아는 곳이 이 행뿐이다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void attachSession(Long paymentId, Point3Session session, String orderToken) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		payment.attachSession(session.id(), session.supplyAmount(), session.vat(), session.taxFreeAmount());
		payment.getOrderGroup().markPayPending(orderToken);
	}

	/**
	 * <b>승인 전 마지막 관문</b> — 검증 4종 + {@code CAPTURE_PENDING} 커밋 (payment-flow 18번, D-004).
	 *
	 * 여기서 걸러야 취소 API 의 시간 제약과 싸우지 않는다. 돈이 나간 뒤에 되돌리는 것보다
	 * 나가기 전에 막는 것이 항상 싸다.
	 *
	 * @return 승인을 호출해야 하면 그 정보, 이미 결제가 끝났으면 {@link Pending#alreadyPaid()}
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Pending markCapturePending(String kakaoId, String orderToken, String sessionId) {
		OrderGroup group = orderGroupRepository.findByOrderToken(orderToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		// ① 소유권
		requireOwner(group, kakaoId);

		// ② 중복 진입 — 이미 끝난 결제를 또 승인하지 않는다
		if (group.isPaid()) {
			return Pending.alreadyPaid(group.getId());
		}

		Payment payment = paymentRepository
				.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.FIRST)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		// ③ 세션 대조 — sessionId 에 서명이 없어 우리가 저장한 값과 비교하는 것이 유일한 검증이다
		if (payment.getSessionId() == null || !payment.getSessionId().equals(sessionId)) {
			log.warn("세션 불일치: orderToken={}, paymentId={}", orderToken, payment.getId());
			throw new BusinessException(ErrorCode.SESSION_MISMATCH);
		}

		if (payment.getStatus() == PaymentStatus.CAPTURED) {
			return Pending.alreadyPaid(group.getId());
		}

		// ④ 홀드 유효성 — 돈이 나가기 전에 막는다. 만료된 재고로 결제를 받으면 초과 판매다
		requireHoldsAlive(group);

		PaymentStatus before = payment.getStatus();
		boolean changed = payment.markCapturePending();
		if (changed) {
			record(payment, before, PaymentStatus.CAPTURE_PENDING, "승인 요청", PaymentActor.USER);
		}

		// changed=false 면 이미 진행 중이다. 승인은 멱등하므로 다시 불러도 되지만,
		// 중복 호출을 줄이려고 그대로 진행한다 — 결과 판정은 어차피 응답을 보고 한다
		return Pending.ready(payment.getId(), group.getId(), payment.getSessionId());
	}

	/**
	 * 출금 확정 (payment-flow 21번). <b>멱등하다.</b>
	 *
	 * 실시간 승인과 대사 배치가 같은 메서드를 부른다 — 배치는 누락된 21번을 대신 실행하는 것이다.
	 * 멱등 가드가 없으면 재고가 두 번 차감된다.
	 *
	 * @return 이번 호출로 확정됐으면 true
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean finalizeCapture(Long paymentId, PaymentActor actor) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		PaymentStatus before = payment.getStatus();
		if (!payment.markCaptured()) {
			return false;
		}
		record(payment, before, PaymentStatus.CAPTURED, "출금 확정", actor);

		OrderGroup group = orderGroupRepository.findByIdForUpdate(payment.getOrderGroup().getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (group.markPaid() && payment.isFirst()) {
			commitHolds(group);
		}
		return true;
	}

	/**
	 * 실패 확정 + 홀드 해제.
	 *
	 * <b>실패가 확인된 경우에만 부른다</b> — 승인 4xx 또는 대사 배치의 세션 조회 결과다.
	 * 타임아웃·5xx 로는 절대 부르지 않는다. 여기서 홀드를 풀면 그 재고가 남에게 팔리고,
	 * 실제로 출금됐다면 구매자는 돈만 나간 상태가 된다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failConfirmed(Long paymentId, String reason, PaymentActor actor) {
		Payment payment = paymentRepository.findByIdForUpdate(paymentId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		PaymentStatus before = payment.getStatus();
		if (!payment.markFailedConfirmed(reason)) {
			return;
		}
		record(payment, before, PaymentStatus.FAILED, reason, actor);

		OrderGroup group = orderGroupRepository.findByIdForUpdate(payment.getOrderGroup().getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (group.markPaymentFailed(reason) && payment.isFirst()) {
			releaseHolds(group);
		}
	}

	/**
	 * 복귀 페이지용 상태 조회. <b>부작용이 없다</b> (D-014).
	 *
	 * 프론트는 결과가 PENDING 인 동안 이 API 만 반복한다. 여기서 승인을 다시 부르거나
	 * 상태를 바꾸면, 조회할 때마다 결제가 일어나는 API 가 된다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public PaymentResultResponse readStatus(String kakaoId, String orderToken) {
		OrderGroup group = orderGroupRepository.findByOrderToken(orderToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));
		requireOwner(group, kakaoId);

		Payment payment = paymentRepository
				.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.FIRST)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		return switch (payment.getStatus()) {
			case CAPTURED -> PaymentResultResponse.paid(orderToken);
			case FAILED -> PaymentResultResponse.failed(orderToken, "결제가 완료되지 않았습니다.");
			// CREATED 도 PENDING 으로 답한다 — 결제창에 들어가기 전이거나 진행 중이다
			case CREATED, CAPTURE_PENDING -> PaymentResultResponse.pending(orderToken);
		};
	}

	/**
	 * 대사 배치가 처리할 미확정 건을 집는다 (D-005).
	 *
	 * <b>배치가 아니라 여기에 두는 이유는 트랜잭션 때문이다.</b> 같은 빈 안에서 부르면
	 * 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않고, 그러면
	 * {@code FOR UPDATE SKIP LOCKED} 가 트랜잭션 없이 실행돼 잠금이 즉시 풀린다.
	 *
	 * id 만 꺼내고 트랜잭션을 닫는다 — point3 조회 동안 락을 쥐고 있으면 안 된다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Long> claimPending(LocalDateTime threshold, int limit) {
		return paymentRepository.findPendingForUpdate(threshold, limit)
				.stream().map(Payment::getId).toList();
	}

	/** 대사 배치가 point3 에 물어보기 위해 꺼내는 값 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public String sessionIdOf(Long paymentId) {
		return paymentRepository.findById(paymentId)
				.map(Payment::getSessionId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
	}

	// ---------------------------------------------------------------- 내부

	/** 홀드를 확정으로 넘긴다. held -= qty, sold += qty */
	private void commitHolds(OrderGroup group) {
		for (StockHold hold : holdsOf(group)) {
			if (!hold.commit()) {
				continue;
			}
			int affected = saleFormRepository.commitHold(hold.getSaleForm().getId(), hold.getQty());
			if (affected == 0) {
				// held 가 그만큼 없다. 데이터가 어긋난 상태라 조용히 넘기지 않는다
				log.error("홀드 확정 실패: holdId={}, saleFormId={}, qty={}",
						hold.getId(), hold.getSaleForm().getId(), hold.getQty());
			}
		}
	}

	/** 홀드를 되돌린다. 멱등 가드가 release() 안에 있다 */
	private void releaseHolds(OrderGroup group) {
		for (StockHold hold : holdsOf(group)) {
			if (!hold.release()) {
				continue;
			}
			int affected = saleFormRepository.releaseHold(hold.getSaleForm().getId(), hold.getQty());
			if (affected == 0) {
				log.warn("홀드 해제 시 재고 반환 실패: holdId={}, saleFormId={}",
						hold.getId(), hold.getSaleForm().getId());
			}
		}
	}

	private List<StockHold> holdsOf(OrderGroup group) {
		List<Long> orderIds = group.getOrders().stream().map(Order::getId).toList();
		return orderIds.isEmpty() ? List.of() : stockHoldRepository.findByOrderIdIn(orderIds);
	}

	/**
	 * 홀드가 전부 살아 있는지 본다.
	 *
	 * 만료 배치가 아직 안 돌았을 수 있으므로 시각도 직접 비교한다 —
	 * 상태만 보면 "HELD 인데 이미 만료된" 홀드를 통과시킨다.
	 */
	private void requireHoldsAlive(OrderGroup group) {
		List<StockHold> holds = holdsOf(group);
		if (holds.isEmpty()) {
			throw new BusinessException(ErrorCode.HOLD_EXPIRED);
		}
		LocalDateTime now = LocalDateTime.now(JpaAuditingConfig.KST);
		for (StockHold hold : holds) {
			if (!hold.isHeld() || hold.getExpiresAt().isBefore(now)) {
				throw new BusinessException(ErrorCode.HOLD_EXPIRED);
			}
		}
	}

	private static void requireOwner(OrderGroup group, String kakaoId) {
		if (!group.getBuyer().getKakaoId().equals(kakaoId)) {
			// 404 가 아니라 403 이다 — 토큰을 안 사람에게 주문의 존재는 이미 알려진 셈이다
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	/** point3 결제창에 뜰 상품명. 여러 폼이면 대표 하나 + 외 N건 */
	private static String productNameOf(OrderGroup group) {
		List<Order> orders = group.getOrders();
		if (orders.isEmpty()) {
			return "주문";
		}
		String first = orders.get(0).getSaleForm().getTitle();
		return orders.size() == 1 ? first : first + " 외 " + (orders.size() - 1) + "건";
	}

	private void record(Payment payment, PaymentStatus from, PaymentStatus to,
	                    String reason, PaymentActor actor) {
		paymentEventRepository.save(PaymentEvent.of(payment.getId(), from, to, reason, actor));
	}

	// ---------------------------------------------------------------- 반환 타입

	/** 세션 생성에 필요한 값. 트랜잭션 밖으로 엔티티를 들고 나가지 않는다 */
	public record Prepared(Long paymentId, Long orderGroupId, int amount, String productName) {
	}

	/**
	 * 승인 호출에 필요한 값.
	 *
	 * @param alreadyPaid 이미 확정된 주문이면 true — 승인을 부르지 않고 성공 응답을 준다
	 */
	public record Pending(Long paymentId, Long orderGroupId, String sessionId, boolean alreadyPaid) {

		static Pending ready(Long paymentId, Long orderGroupId, String sessionId) {
			return new Pending(paymentId, orderGroupId, sessionId, false);
		}

		static Pending alreadyPaid(Long orderGroupId) {
			return new Pending(null, orderGroupId, null, true);
		}
	}
}
