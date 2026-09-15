package store.moeum.moeum.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.BuyerRefundAccount;
import store.moeum.moeum.buyer.domain.BuyerRefundAccountRepository;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.domain.SellerPaymentCounts;
import store.moeum.moeum.payment.domain.SellerPaymentStatus;
import store.moeum.moeum.payment.dto.SellerPaymentDetailResponse;
import store.moeum.moeum.payment.dto.SellerPaymentPageResponse;
import store.moeum.moeum.payment.refund.OrderRefundReader;
import store.moeum.moeum.payment.refund.OrderRefundService;
import store.moeum.moeum.payment.refund.Refund;
import store.moeum.moeum.payment.refund.RefundRepository;
import store.moeum.moeum.payment.refund.RefundService;
import store.moeum.moeum.payment.refund.SellerCancelView;
import store.moeum.moeum.payment.refund.RefundWriter;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 셀러 결제 내역 (와이어프레임 G10) 과 정산 후 환불 처리 (S14) — D-059.
 *
 * <b>소유권은 쿼리에 박아서 지킨다.</b> 목록은 {@code WHERE g.seller_id = :sellerId} 라
 * 남의 결제가 결과에 섞일 길이 없고, 상세·취소는 결제번호로 찾은 뒤 셀러를 대조한다 —
 * 셀러 주문 목록(G6)과 같은 규칙이다.
 *
 * <b>취소는 여기서 새로 짜지 않는다.</b> 구매자 취소가 쓰는 엔진을 그대로 부르고
 * {@code requested_by} 만 SELLER 로 바뀐다. 취소 구간 판정도 같은 {@code RefundPolicy} 다 —
 * 갈라지면 구매자는 못 하는 취소를 셀러가 할 수 있게 된다.
 *
 * <b>쓰기 경로에는 {@code @Transactional} 을 걸지 않는다.</b> 취소는 point3 를 부르고
 * (CLAUDE.md 규칙 1), 직접 환불 표시는 {@link RefundWriter} 가 자기 트랜잭션을 열어
 * 커밋한다 — 밖에서 감싸면 커밋된 결과를 바깥 트랜잭션이 못 본다(REPEATABLE READ).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerPaymentService {

	/** 한 번에 내려주는 줄 수. 무한 스크롤이라 넉넉할 필요가 없다 */
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	/** 화면에 그대로 띄우는 안내. 서버가 주는 이유는 문구가 곧 정책이라서다 */
	private static final String MANUAL_NOTICE = "정산 후에는 구매자에게 직접 이체해야 해요";

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final ShippingRepository shippingRepository;
	private final SaleFormRepository saleFormRepository;
	private final BuyerRefundAccountRepository refundAccountRepository;
	private final OrderRefundReader refundReader;
	private final OrderRefundService orderRefundService;
	private final RefundService refundService;
	private final RefundWriter refundWriter;
	private final SellerService sellerService;

	// ---------------------------------------------------------------- 목록

	/**
	 * 목록. 칩 숫자를 함께 내려준다.
	 *
	 * 응답 조립을 이 트랜잭션 안에서 끝낸다 — 밖에서 만들면 지연 로딩이 터진다.
	 *
	 * @param status 칩. null 이면 전체다
	 */
	@Transactional(readOnly = true)
	public SellerPaymentPageResponse list(String kakaoId, SellerPaymentStatus status, Long saleFormId,
	                                      String q, int page, int size) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		requireOwnedForm(seller, saleFormId);

		String keyword = likePattern(q);
		String statusName = (status == null) ? null : status.name();

		Page<Payment> payments = paymentRepository.findSellerPayments(
				seller.getId(), statusName, saleFormId, keyword,
				PageRequest.of(Math.max(page, 0), clampSize(size)));

		Map<Long, List<Refund>> refunds = refundsOf(payments.getContent());
		Map<Long, Shipping> shippings = shippingsOf(payments.getContent());

		List<SellerPaymentPageResponse.SellerPaymentItem> items = payments.getContent().stream()
				.map(payment -> SellerPaymentPageResponse.itemOf(payment,
						shippings.get(payment.getOrderGroup().getId()),
						statusOf(payment, refunds)))
				.toList();

		SellerPaymentCounts counts = SellerPaymentCounts.of(
				paymentRepository.tallySellerPayments(seller.getId(), saleFormId, keyword));

		return new SellerPaymentPageResponse(
				SellerPaymentPageResponse.countsOf(counts),
				items,
				new SellerPaymentPageResponse.PageInfo(payments.getNumber(), payments.getSize(),
						payments.getTotalElements(), payments.getTotalPages(), payments.hasNext()));
	}

	// ---------------------------------------------------------------- 상세

	/** 드로어(G10 상세)와 환불 처리(S14)가 같이 쓴다 */
	@Transactional(readOnly = true)
	public SellerPaymentDetailResponse detail(String kakaoId, String paymentNo) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		return detailOf(seller, findOwned(seller, paymentNo));
	}

	// ---------------------------------------------------------------- 수행

	/**
	 * 결제 취소 (G10 드로어의 [결제 취소]).
	 *
	 * <b>차수 한쪽만 취소하지 않는다.</b> 1차금만 되돌리면 잔금은 받은 채 상품값만 돌려준
	 * 꼴이 되고, 2차금만 되돌리면 그 반대가 된다 — 어느 줄에서 눌렀든 그 주문을 취소한다.
	 * 장바구니 주문에서 폼 하나만 빼려면 {@code orderId} 를 채운다.
	 *
	 * @return 구매자 취소와 같은 응답이다. <b>PROCESSING 은 실패가 아니다</b> —
	 *         다시 보내면 두 번 환불되므로 프론트는 재시도 버튼 대신 상세를 다시 불러야 한다
	 */
	public OrderRefundResponse cancel(String kakaoId, String paymentNo, Long orderId, String reason) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		String orderNo = orderNoOf(paymentNo);

		log.info("셀러 결제 취소: sellerId={}, paymentNo={}, orderId={}",
				seller.getId(), paymentNo, orderId);

		return orderRefundService.refundBySeller(seller.getId(), orderNo, orderId, reason);
	}

	/**
	 * 정산 후 직접 이체를 마쳤다고 표시한다 (S14 의 [환불 완료로 변경]).
	 *
	 * <b>돈이 실제로 나갔는지 우리가 확인할 방법은 없다.</b> point3 를 거치지 않는 이체라
	 * 이 표시는 셀러의 자기 신고고, 그래서 {@code manual_refunded_at} 에 누른 시각을 남긴다.
	 *
	 * <b>멱등하다.</b> 두 번 눌러도 재고가 두 번 돌아가지 않는다.
	 *
	 * @return 이번 호출로 처리됐으면 true. false 면 이미 끝난 건이다
	 */
	public boolean completeManualRefund(String kakaoId, String paymentNo) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		Long refundId = manualPendingRefundId(seller, paymentNo);

		boolean done = refundWriter.completeManually(refundId);
		if (done) {
			log.info("정산 후 직접 환불 처리: sellerId={}, paymentNo={}, refundId={}",
					seller.getId(), paymentNo, refundId);
		}
		return done;
	}

	/**
	 * 처리할 환불 건을 집는다. 접수된 것이 없으면 404 다.
	 *
	 * 같은 결제에 접수가 여러 번 쌓일 수 있어 마지막 건을 집는다 — 화면이 보여 주는 것도
	 * 마지막 건이다.
	 */
	private Long manualPendingRefundId(Seller seller, String paymentNo) {
		Payment payment = findOwned(seller, paymentNo);

		return refundRepository.findByPaymentIdOrderByIdAsc(payment.getId()).stream()
				.filter(Refund::isManualPending)
				.reduce((first, second) -> second)
				.map(Refund::getId)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND,
						"직접 환불로 접수된 건이 아닙니다."));
	}

	// ---------------------------------------------------------------- 조립

	private SellerPaymentDetailResponse detailOf(Seller seller, Payment payment) {
		OrderGroup group = payment.getOrderGroup();
		List<Refund> refunds = refundRepository.findByPaymentIdOrderByIdAsc(payment.getId());
		SellerPaymentStatus status = SellerPaymentStatus.of(payment, refunds);
		Shipping shipping = shippingRepository.findByOrderGroupId(group.getId()).orElse(null);

		return new SellerPaymentDetailResponse(
				payment.paymentNo(),
				group.getOrderNo(),
				payment.getPhase(),
				SellerPaymentPageResponse.phaseLabel(payment.getPhase()),
				group.representativeTitle(),
				itemLines(group),
				shipping != null ? shipping.getRecipientName() : group.getBuyer().getNickname(),
				payment.getAmount(),
				payment.getCapturedAt(),
				// point3 가 승인 응답에 수단을 주지 않는다. 없는 값을 지어내지 않는다
				null,
				status,
				status.label(),
				cancelSection(seller, group),
				manualRefundSection(group, refunds));
	}

	private static List<SellerPaymentDetailResponse.ItemLine> itemLines(OrderGroup group) {
		return group.getOrders().stream()
				.flatMap(order -> order.getItems().stream().map(item -> itemLine(order, item)))
				.toList();
	}

	private static SellerPaymentDetailResponse.ItemLine itemLine(Order order, OrderItem item) {
		return new SellerPaymentDetailResponse.ItemLine(
				order.getSaleForm().getTitle(),
				item.getProductName(),
				item.getOptionName(),
				item.getQty(),
				order.isCanceled());
	}

	/**
	 * 버튼을 켤지 말지를 <b>취소가 실제로 타는 경로에 물어서</b> 정한다.
	 *
	 * 조건을 여기 따로 적으면 "켜져 있는데 누르면 튕기는" 버튼이 생긴다. 계획을 세워 보는
	 * 것은 읽기뿐이라 부작용이 없고, 거기서 통과한 계획이 곧 취소가 실행할 계획이다.
	 *
	 * EOB 시간대(23:30~00:30)도 끈 것으로 본다 — 실패가 아니라 "00:30 이후에 가능" 이고,
	 * {@code blockedUntil} 로 언제 열리는지를 같이 준다.
	 */
	private SellerPaymentDetailResponse.CancelSection cancelSection(Seller seller, OrderGroup group) {
		LocalDateTime blockedUntil = refundService.blockedUntil();
		SellerCancelView view = refundReader.sellerCancelView(seller.getId(), group.getOrderNo());

		if (blockedUntil != null) {
			return new SellerPaymentDetailResponse.CancelSection(false,
					ErrorCode.REFUND_EOB_BLOCKED.message(), blockedUntil, view.refundableAmount());
		}
		return new SellerPaymentDetailResponse.CancelSection(
				view.cancelable(), view.blockedReason(), null, view.refundableAmount());
	}

	/**
	 * 정산 후 환불 칸. 접수된 적이 없으면 비워서 보낸다 — 화면이 이 값으로 S14 를 그린다.
	 *
	 * 계좌번호는 <b>아직 이체하지 않은 건에만</b> 싣는다. 끝난 건의 번호가 계속 내려갈
	 * 이유가 없고, 여기가 전체 번호가 나가는 유일한 자리다.
	 */
	private SellerPaymentDetailResponse.ManualRefundSection manualRefundSection(
			OrderGroup group, List<Refund> refunds) {

		Optional<Refund> manual = refunds.stream()
				.filter(Refund::isSettledManual)
				.reduce((first, second) -> second);

		if (manual.isEmpty()) {
			return null;
		}
		Refund refund = manual.get();
		boolean completed = refund.getManualRefundedAt() != null;

		return new SellerPaymentDetailResponse.ManualRefundSection(
				refund.getCreatedAt(),
				refund.getAmount(),
				completed,
				refund.getManualRefundedAt(),
				completed ? "환불 완료" : "환불 대기",
				MANUAL_NOTICE,
				completed ? null : accountOf(group));
	}

	private SellerPaymentDetailResponse.Account accountOf(OrderGroup group) {
		return refundAccountRepository.findByBuyerId(group.getBuyer().getId())
				.map(SellerPaymentService::account)
				.orElse(null);
	}

	private static SellerPaymentDetailResponse.Account account(BuyerRefundAccount account) {
		return new SellerPaymentDetailResponse.Account(
				account.getBank(), account.getAccountNo(), account.getHolderName());
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 결제번호로 찾고 셀러 소유인지 본다.
	 *
	 * 없는 번호와 남의 번호를 똑같이 404 로 돌려준다 — 403 이면 "그 번호에 결제가 있긴 하다"
	 * 가 새어 나가 번호를 훑어 남의 거래를 알아낼 수 있다.
	 */
	private Payment findOwned(Seller seller, String paymentNo) {
		Payment payment = paymentRepository
				.findByOrderNoAndPhase(orderNoOf(paymentNo), phaseOf(paymentNo))
				.orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

		if (!payment.getOrderGroup().getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		return payment;
	}

	/**
	 * 결제번호 {@code ORD-260828-92-1} 에서 주문번호를 떼어 낸다.
	 *
	 * 주문번호가 {@code ORD-yyMMdd-id} 라 붙임표가 이미 둘이다. 마지막 조각만 차수로 본다.
	 */
	private static String orderNoOf(String paymentNo) {
		return paymentNo.substring(0, lastDash(paymentNo));
	}

	private static PaymentPhase phaseOf(String paymentNo) {
		String suffix = paymentNo.substring(lastDash(paymentNo) + 1);
		return switch (suffix) {
			case "1" -> PaymentPhase.FIRST;
			case "2" -> PaymentPhase.SECOND;
			// 모양이 틀린 번호는 없는 번호와 같이 다룬다
			default -> throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		};
	}

	private static int lastDash(String paymentNo) {
		int index = (paymentNo == null) ? -1 : paymentNo.lastIndexOf('-');
		if (index <= 0) {
			throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
		}
		return index;
	}

	private static SellerPaymentStatus statusOf(Payment payment, Map<Long, List<Refund>> refunds) {
		return SellerPaymentStatus.of(payment, refunds.getOrDefault(payment.getId(), List.of()));
	}

	/** 줄마다 부르면 목록 크기만큼 조회가 나간다. 한 번에 끌어와 결제 id 로 접는다 */
	private Map<Long, List<Refund>> refundsOf(List<Payment> payments) {
		if (payments.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = payments.stream().map(Payment::getId).toList();
		return refundRepository.findByPaymentIdIn(ids).stream()
				.collect(Collectors.groupingBy(Refund::getPaymentId));
	}

	private Map<Long, Shipping> shippingsOf(List<Payment> payments) {
		if (payments.isEmpty()) {
			return Map.of();
		}
		List<Long> ids = payments.stream()
				.map(payment -> payment.getOrderGroup().getId()).distinct().toList();

		return shippingRepository.findByOrderGroupIdIn(ids).stream()
				.collect(Collectors.toMap(s -> s.getOrderGroup().getId(), Function.identity()));
	}

	/**
	 * 판매별 필터로 들어온 폼이 이 셀러 것인지 본다.
	 *
	 * 없는 폼과 남의 폼을 똑같이 404 로 돌려준다 — G6 와 같은 규칙이다.
	 */
	private void requireOwnedForm(Seller seller, Long saleFormId) {
		if (saleFormId == null) {
			return;
		}
		SaleForm form = saleFormRepository.findById(saleFormId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		if (!form.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
	}

	/**
	 * 검색어를 LIKE 패턴으로 만든다.
	 *
	 * <b>와일드카드 문자를 escape 한다.</b> 그냥 끼워 넣으면 셀러가 친 퍼센트 기호가
	 * 와일드카드로 동작해서 결제 전체가 걸린다. 쿼리는 escape 문자를 지정해 받는다.
	 */
	private static String likePattern(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String escaped = q.trim()
				.replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
	}

	private static int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
