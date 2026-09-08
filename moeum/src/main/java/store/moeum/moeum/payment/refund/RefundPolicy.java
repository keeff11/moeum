package store.moeum.moeum.payment.refund;

import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.util.EnumSet;
import java.util.Set;

/**
 * 구매자가 스스로 취소할 수 있는 구간 (D-025).
 *
 * <pre>
 *   GROUP  PAID · RECRUITING · CLOSED 까지
 *          PRODUCING 부터 불가 — 셀러가 이미 그 수량으로 발주했다
 *
 *   SOLO   ARRIVED 까지 (= 발송 전)
 *          있는 재고를 파는 것이라 발주가 없다. 나가기 전이면 되돌릴 수 있다
 * </pre>
 *
 * <b>재고를 되돌리는가와는 다른 질문이다.</b> 취소되는 구간이라고 재고가 같이 돌아오지는 않는다 —
 * GROUP 은 폼이 마감되면 취소는 되지만 그 자리를 다시 팔 수 없어 되돌리지 않는다 (D-024).
 */
public final class RefundPolicy {

	/** 공동구매 — 발주(PRODUCING) 전까지 */
	private static final Set<OrderStatus> GROUP_CANCELABLE =
			EnumSet.of(OrderStatus.PAID, OrderStatus.RECRUITING, OrderStatus.CLOSED);

	/**
	 * 단독판매 — 발송(SHIPPED) 전까지.
	 *
	 * 상태 머신에 '배송 준비' 단계가 아직 없어 발송 직전까지로 뒀다.
	 * 7단계에서 배송 상태가 생기면 그 값을 이 집합에서 빼면 컷이 앞으로 당겨진다.
	 */
	private static final Set<OrderStatus> SOLO_CANCELABLE =
			EnumSet.of(OrderStatus.PAID, OrderStatus.RECRUITING, OrderStatus.CLOSED,
					OrderStatus.PRODUCING, OrderStatus.ARRIVED);

	private RefundPolicy() {
	}

	/** 취소할 수 없으면 그 이유, 취소할 수 있으면 null */
	public static String blockReason(Order order) {
		OrderStatus status = order.getStatus();

		if (status == OrderStatus.CANCELED) {
			return "이미 취소된 주문입니다.";
		}
		if (status == OrderStatus.EXPIRED) {
			return "만료된 주문입니다.";
		}
		if (status == OrderStatus.CREATED) {
			// 결제 전이다. 취소가 아니라 홀드 해제로 처리할 일이다
			return "아직 결제되지 않은 주문입니다.";
		}

		if (order.getSaleForm().getSaleType() == SaleType.SOLO) {
			return SOLO_CANCELABLE.contains(status)
					? null
					: "이미 배송이 시작되어 취소할 수 없습니다. 판매자에게 문의해 주세요.";
		}
		return GROUP_CANCELABLE.contains(status)
				? null
				: "발주가 시작되어 취소할 수 없습니다. 판매자에게 문의해 주세요.";
	}

	public static boolean cancelable(Order order) {
		return blockReason(order) == null;
	}
}
