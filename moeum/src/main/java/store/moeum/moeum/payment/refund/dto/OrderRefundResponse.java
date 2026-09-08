package store.moeum.moeum.payment.refund.dto;

import java.util.List;

/**
 * 주문 취소 결과.
 *
 * <b>{@code PROCESSING} 은 실패가 아니다.</b> 취소는 멱등하지 않아 결과를 모를 때
 * 다시 보내면 두 번 환불된다. 프론트는 이 값을 받으면 재요청 버튼을 열지 말고
 * 취소 가능 조회를 다시 부르게 해야 한다.
 *
 * @param status  두 건 중 하나라도 미확정이면 전체가 {@code PROCESSING} 이다
 * @param details 1차금·2차금 각각의 결과. 진행 상황을 보여줄 때만 쓴다
 */
public record OrderRefundResponse(String orderToken,
                                  Status status,
                                  String message,
                                  int refundedAmount,
                                  List<Detail> details) {

	public enum Status {
		/** 두 건 다 환불됐다 */
		COMPLETED,
		/** 결과 확인 중. 대사 배치가 끝낸다 — 다시 요청하게 하면 안 된다 */
		PROCESSING,
		FAILED,
		/** 정산이 끝나 자동 취소가 불가능하다. 셀러 직접 환불로 넘어간다 */
		SETTLED_MANUAL
	}

	/** @param phase FIRST 또는 SECOND */
	public record Detail(String phase, Long refundId, Status status, int amount) {
	}
}
