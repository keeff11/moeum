package store.moeum.moeum.payment.refund.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
public record OrderRefundResponse(

		@Schema(description = "이 주문을 가리키는 토큰")
		String orderToken,

		@Schema(description = "취소 결과. PROCESSING 은 실패가 아니다 — 다시 요청하면 두 번 환불된다")
		Status status,

		@Schema(description = "사용자에게 그대로 보여도 되는 안내 문구")
		String message,

		@Schema(description = "이번에 실제로 환불이 확정된 금액. 확인 중인 건은 빠져 있다",
				example = "64000")
		int refundedAmount,

		@Schema(description = "1차금·2차금 각각의 결과. 진행 상황을 보여줄 때만 쓴다")
		List<Detail> details) {

	@Schema(description = """
			COMPLETED=환불 완료 · PROCESSING=결과 확인 중(재요청 금지, 서버 배치가 끝낸다)
			· FAILED=취소되지 않음 · SETTLED_MANUAL=정산이 끝나 자동 취소 불가(판매자 문의 안내)""")
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
	@Schema(description = "결제 차수별 취소 결과")
	public record Detail(
			@Schema(description = "결제 차수. FIRST=1차금, SECOND=2차금", example = "FIRST") String phase,
			@Schema(description = "취소 건 id", example = "9") Long refundId,
			@Schema(description = "이 차수의 취소 결과") Status status,
			@Schema(description = "이 차수에서 취소 요청한 금액", example = "40000") int amount) {
	}
}
