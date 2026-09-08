package store.moeum.moeum.outbox.domain;

/**
 * 밖으로 알릴 사실. <b>전부 이미 확정된 것이다</b> (D-012) —
 * "결제될 예정" 같은 미확정 상태는 여기 들어오지 않는다.
 */
public enum OutboxEventType {

	/** 1차금 결제 완료 */
	ORDER_PAID,

	/**
	 * 2차금을 청구할 수 있게 됐다 — 묶음의 모든 폼이 입고됐다.
	 *
	 * <b>이 알림이 곧 결제 요청이다.</b> 유실되면 구매자는 잔금을 낼 줄 모르고,
	 * 셀러는 미수로 남은 이유를 알 수 없다. 다른 이벤트보다 중요하다.
	 */
	SECOND_PAYMENT_DUE,

	/** 2차금 결제 완료 */
	SECOND_PAID,

	/** 취소·환불 완료 */
	REFUND_COMPLETED
}
