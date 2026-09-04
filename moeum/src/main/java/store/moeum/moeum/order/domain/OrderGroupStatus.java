package store.moeum.moeum.order.domain;

/** order_group.status */
public enum OrderGroupStatus {
	/** 재고 홀드만 잡힌 상태. checkout_session 이 곧 이것이다 */
	CREATED,
	PAY_PENDING,
	CONFIRMING,
	PAID,
	SECOND_PENDING,
	SECOND_PAID,
	SHIPPED,
	CANCELED,
	EXPIRED,
	FAILED
}
