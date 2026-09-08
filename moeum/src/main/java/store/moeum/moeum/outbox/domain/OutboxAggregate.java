package store.moeum.moeum.outbox.domain;

/** outbox.aggregate_type — 이 이벤트가 어느 것에 대한 사실인가 */
public enum OutboxAggregate {

	ORDER_GROUP,
	ORDER,
	SALE_FORM,
	PAYMENT
}
