package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;
import store.moeum.moeum.payment.refund.dto.RefundableResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 주문 취소의 정책 층 — 구매자가 보는 "취소" 를 결제 취소 1~2건으로 번역한다.
 *
 * <pre>
 *   구매자: "이 폼 취소"
 *      ↓  OrderRefundReader  소유권 · 취소 가능 구간 · 금액 · 배송비
 *      ↓  RefundService      1차금 취소   (point3)
 *      ↓  RefundService      2차금 취소   (point3, 있으면)
 *      ↓  합성               둘 중 하나라도 미확정이면 전체가 PROCESSING
 * </pre>
 *
 * <b>{@code @Transactional} 이 없다.</b> {@link RefundService} 가 point3 를 부르기 때문이다
 * (CLAUDE.md 규칙 1). 읽기는 {@link OrderRefundReader}, 쓰기는 {@link RefundWriter} 가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundService {

	private final OrderRefundReader reader;
	private final RefundService refundService;

	/** 취소 화면 조회. 부작용이 없어 몇 번을 불러도 된다 */
	public RefundableResponse refundable(SessionUser user, String orderToken) {
		RefundableResponse view = reader.view(user.kakaoId(), orderToken);

		return new RefundableResponse(view.orderToken(), view.refundable(), view.blockedReason(),
				refundService.blockedUntil(), view.shippingFee(), view.shippingFeeRefundable(), view.items());
	}

	/**
	 * 취소를 실행한다.
	 *
	 * <b>1차금이 확정 거절되면 2차금은 보내지 않는다.</b> 취소할 수 없는 주문이라는 뜻이라
	 * 잔금만 돌려주면 상품값은 받은 채 배송비만 환불한 꼴이 된다.
	 * 반대로 1차금이 미확정({@code PROCESSING}) 이면 2차금은 그대로 진행한다 —
	 * 실패가 아니라 결과를 모르는 것뿐이고, 배치가 1차금을 마저 끝낸다.
	 */
	public OrderRefundResponse refund(SessionUser user, String orderToken, Long orderId, String reason) {
		RefundPlan plan = reader.plan(user.kakaoId(), orderToken, orderId);
		return execute(plan, note(reason, "구매자 요청"), RefundRequester.BUYER);
	}

	/**
	 * 시스템이 거는 주문 취소 — 목표수량 미달 등 (D-026).
	 *
	 * <b>소유권도 취소 구간도 보지 않는다.</b> 구매자 잘못이 아니라 폼이 성립하지 않은 것이다.
	 * 취소할 것이 없으면 비어 있는 값을 준다 — 배치가 주문 하나 때문에 멈추면 안 된다.
	 */
	public Optional<OrderRefundResponse> cancelByOrder(Long orderId, String reason) {
		return reader.systemPlan(orderId)
				.map(plan -> execute(plan, note(reason, "판매자 사정으로 취소"), RefundRequester.SYSTEM));
	}

	// ---------------------------------------------------------------- 실행

	private OrderRefundResponse execute(RefundPlan plan, String note, RefundRequester requester) {
		List<OrderRefundResponse.Detail> details = new ArrayList<>(2);

		if (plan.hasFirst()) {
			RefundService.RefundResult first = refundService.refund(
					plan.firstPaymentId(), plan.orderId(), plan.firstAmount(), note, requester);
			details.add(detail("FIRST", first, plan.firstAmount()));

			if (first.status() == RefundService.RefundResult.Status.FAILED) {
				log.info("1차금 취소가 거절돼 2차금은 보내지 않는다: orderToken={}", plan.orderToken());
				return compose(plan.orderToken(), details);
			}
		}

		if (plan.hasSecond()) {
			RefundService.RefundResult second = refundService.refund(
					plan.secondPaymentId(), plan.orderId(), plan.secondAmount(), note, requester);
			details.add(detail("SECOND", second, plan.secondAmount()));
		}

		return compose(plan.orderToken(), details);
	}

	private static String note(String reason, String fallback) {
		return (reason == null || reason.isBlank()) ? fallback : reason;
	}

	// ---------------------------------------------------------------- 합성

	/**
	 * 두 건의 결과를 하나로 합친다.
	 *
	 * 우선순위는 <b>안내가 더 필요한 쪽</b>이다 — 정산 완료가 가장 위고, 그다음이 미확정이다.
	 * "하나는 됐으니 성공" 으로 뭉개면 나머지 한 건이 조용히 묻힌다.
	 */
	private static OrderRefundResponse compose(String orderToken, List<OrderRefundResponse.Detail> details) {
		OrderRefundResponse.Status status = worst(details);
		int refunded = details.stream()
				.filter(d -> d.status() == OrderRefundResponse.Status.COMPLETED)
				.mapToInt(OrderRefundResponse.Detail::amount)
				.sum();

		return new OrderRefundResponse(orderToken, status, messageOf(status), refunded, details);
	}

	private static OrderRefundResponse.Status worst(List<OrderRefundResponse.Detail> details) {
		List<OrderRefundResponse.Status> statuses = details.stream()
				.map(OrderRefundResponse.Detail::status).toList();

		if (statuses.contains(OrderRefundResponse.Status.SETTLED_MANUAL)) {
			return OrderRefundResponse.Status.SETTLED_MANUAL;
		}
		if (statuses.contains(OrderRefundResponse.Status.PROCESSING)) {
			return OrderRefundResponse.Status.PROCESSING;
		}
		if (statuses.contains(OrderRefundResponse.Status.FAILED)) {
			return OrderRefundResponse.Status.FAILED;
		}
		return OrderRefundResponse.Status.COMPLETED;
	}

	private static String messageOf(OrderRefundResponse.Status status) {
		return switch (status) {
			case COMPLETED -> "취소가 완료되었습니다.";
			case PROCESSING -> "취소를 처리하고 있습니다. 잠시 후 상태를 확인해 주세요.";
			case FAILED -> "취소가 완료되지 않았습니다.";
			case SETTLED_MANUAL -> "정산이 완료되어 자동 취소가 어렵습니다. 판매자에게 문의해 주세요.";
		};
	}

	private static OrderRefundResponse.Detail detail(String phase, RefundService.RefundResult result, int amount) {
		return new OrderRefundResponse.Detail(phase, result.refundId(),
				OrderRefundResponse.Status.valueOf(result.status().name()), amount);
	}
}
