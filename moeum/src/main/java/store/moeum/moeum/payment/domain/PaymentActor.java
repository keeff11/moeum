package store.moeum.moeum.payment.domain;

/** payment_event.actor — 상태를 바꾼 주체 */
public enum PaymentActor {

	/** 구매자 요청으로 바뀐 전이 */
	USER,

	/** 대사 배치가 뒤늦게 확정한 전이. 실시간 처리가 누락된 흔적이다 */
	BATCH,

	SELLER,
	SYSTEM
}
