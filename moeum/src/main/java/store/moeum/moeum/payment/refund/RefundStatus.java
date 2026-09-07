package store.moeum.moeum.payment.refund;

/**
 * refund.status
 *
 * <pre>
 *   PROCESSING ──▶ COMPLETED
 *        └───────▶ FAILED
 * </pre>
 *
 * <b>PROCESSING 은 "진행 중" 이자 "결과 모름" 이다.</b> 승인의 CAPTURE_PENDING 과 같은 자리고,
 * 이 상태에서는 <b>절대 새 취소를 만들지 않는다</b> — 취소는 멱등하지 않아 두 번 환불된다.
 * 대사 배치가 조회 → resume 으로 확정한다.
 */
public enum RefundStatus {

	/** 요청했고 결과를 모른다. 새 취소 금지 */
	PROCESSING,
	/** 환불 완료 */
	COMPLETED,
	/** 확인된 실패. 다시 시도할 수 있다 */
	FAILED;

	public boolean isPending() {
		return this == PROCESSING;
	}
}
