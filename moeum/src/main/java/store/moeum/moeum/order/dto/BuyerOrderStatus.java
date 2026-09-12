package store.moeum.moeum.order.dto;

import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderStatus;

import java.util.Comparator;
import java.util.List;

/**
 * 구매자 주문 카드의 상태 배지 (와이어프레임 B13).
 *
 * <b>{@link SellerOrderStatus} 와 값이 다르다.</b> 같은 주문이라도 보는 사람에 따라
 * 알아야 할 것이 다르기 때문이다 — 셀러에게 {@code PAID} 는 "아직 청구 못 하는 건" 이지만
 * 구매자에게는 "모집 중인지 제작 중인지" 가 중요하다. 셀러 배지를 그대로 쓰면
 * 진행 단계가 통째로 사라진다.
 *
 * <b>진행 단계는 묶음이 아니라 주문(폼)에 있다.</b> {@code order_group.status} 는 결제
 * 단계만 말하고, 모집·발주·제작·입고는 {@code orders.status} 가 판매 폼별로 따로 돈다.
 * 한 줄이 묶음 하나라(D-033) 폼이 여럿이면 <b>가장 덜 진행된 것</b>을 쓴다 —
 * 구매자가 기다리고 있는 것이 그것이다.
 */
public enum BuyerOrderStatus {

	PAYMENT_WAITING("결제 대기"),

	/** 결제는 됐고 아직 모집 기간이다 */
	RECRUITING("모집 중"),

	/** 모집이 끝났고 발주 전이다 */
	CLOSED("모집 마감"),

	PRODUCING("제작 중"),

	/** 입고돼서 잔금을 낼 수 있다. 구매자가 행동해야 하는 유일한 상태다 */
	SECOND_UNPAID("입고·2차금"),

	/** 2차금까지 냈고 발송 전이다 */
	PREPARING("배송 준비 중"),

	SHIPPED("발송 완료"),

	CANCELED("취소됨"),

	FAILED("결제 실패"),

	/** 결제만 끝나고 아직 진행 단계가 올라가지 않았다 */
	PAID("결제 완료");

	private final String label;

	BuyerOrderStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * <b>2차금이 결제 단계보다 우선한다.</b> 잔금을 낼 수 있게 된 순간이 구매자가
	 * 행동해야 하는 유일한 시점이라, 그게 가려지면 미납이 쌓인다.
	 *
	 * <b>잔금이 없는 묶음은 입고가 곧 배송 준비다</b> (D-046). 단독 판매의 진행 단계는
	 * 결제완료 → 준비중 → 발송이고 <b>제작 중이라는 단계가 아예 없다</b> (domain.md 1절).
	 * 그런데 아래 {@code progressOf} 는 ARRIVED 를 "다른 폼이 아직 입고 전" 으로 읽어
	 * 제작 중으로 내린다 — 폼이 하나뿐인 단독 판매에는 맞지 않는 전제다.
	 */
	public static BuyerOrderStatus of(OrderGroup group) {
		return switch (group.getStatus()) {
			// CONFIRMING 은 선언만 돼 있고 지금 이 값을 넣는 코드가 없다.
			// 승인 중이라는 뜻이니 구매자에게는 결제 대기와 같다
			case PAY_PENDING, CONFIRMING -> PAYMENT_WAITING;
			case PAID, SECOND_PENDING -> {
				if (group.isSecondPaymentDue()) {
					yield SECOND_UNPAID;
				}
				yield group.isReadyToShipWithoutSecond() ? PREPARING : progressOf(group);
			}
			case SECOND_PAID -> PREPARING;
			case SHIPPED -> SHIPPED;
			case CANCELED -> CANCELED;
			case FAILED -> FAILED;

			// CREATED · EXPIRED 는 목록 쿼리가 이미 걸러 낸다. 여기 오면 쿼리가 바뀐 것이다
			case CREATED, EXPIRED -> throw new IllegalStateException(
					"주문 목록에 나올 수 없는 상태다: " + group.getStatus());
		};
	}

	/**
	 * 살아 있는 주문 중 가장 덜 진행된 것의 단계.
	 *
	 * 전부 취소된 묶음은 위에서 {@code CANCELED} 로 걸러지지만, 일부만 취소된 묶음은
	 * 여기로 온다 — 그때는 남은 주문만 본다.
	 */
	private static BuyerOrderStatus progressOf(OrderGroup group) {
		List<Order> alive = group.activeOrders();

		return alive.stream()
				.map(Order::getStatus)
				.min(Comparator.comparingInt(BuyerOrderStatus::rank))
				.map(BuyerOrderStatus::fromOrder)
				.orElse(PAID);
	}

	/** 진행 순서. 작을수록 덜 진행된 것이다 */
	private static int rank(OrderStatus status) {
		return switch (status) {
			case CREATED -> 0;
			case PAID -> 1;
			case RECRUITING -> 2;
			case CLOSED -> 3;
			case PRODUCING -> 4;
			case ARRIVED -> 5;
			case SHIPPED -> 6;
			// 취소·만료는 activeOrders() 가 이미 뺐다. 순서에 끼면 늘 최솟값이 되어 버린다
			case CANCELED, EXPIRED -> Integer.MAX_VALUE;
		};
	}

	private static BuyerOrderStatus fromOrder(OrderStatus status) {
		return switch (status) {
			case RECRUITING -> RECRUITING;
			case CLOSED -> CLOSED;
			case PRODUCING -> PRODUCING;
			// 입고됐는데 SECOND_UNPAID 로 안 왔다면 다른 폼이 아직 입고 전이다
			case ARRIVED -> PRODUCING;
			case SHIPPED -> SHIPPED;
			default -> PAID;
		};
	}
}
