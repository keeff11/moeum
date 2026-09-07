package store.moeum.moeum.payment.refund;

/** refund.requested_by — 취소를 요청한 주체 */
public enum RefundRequester {

	BUYER,
	SELLER,
	/** 목표수량 미달 자동 취소 등 시스템이 건 취소 */
	SYSTEM
}
