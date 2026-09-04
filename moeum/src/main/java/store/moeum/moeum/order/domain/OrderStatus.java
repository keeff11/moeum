package store.moeum.moeum.order.domain;

/** orders.status — 상태 머신이 판매 폼별로 따로 돈다 */
public enum OrderStatus {
	CREATED,
	PAID,
	RECRUITING,
	CLOSED,
	PRODUCING,
	ARRIVED,
	SHIPPED,
	CANCELED,
	EXPIRED
}
