package store.moeum.moeum.payment.refund.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 취소 요청 본문.
 *
 * <b>금액을 받지 않는다.</b> 얼마를 돌려줄지는 서버가 DB 의 주문 금액에서 계산한다
 * (CLAUDE.md 규칙 9). 클라이언트가 보낸 금액을 그대로 취소하면 조작할 수 있다.
 *
 * @param orderId 폼 하나만 취소하면 그 주문 id. null 이면 남은 폼 전부
 */
public record OrderRefundRequest(

		@Schema(description = "취소할 주문 id. 주문 조회 응답의 orders[].orderId 다. "
				+ "비우면 아직 취소되지 않은 폼 전부를 취소한다", example = "44")
		Long orderId,

		@Schema(description = "취소 사유. 비우면 '구매자 요청'으로 기록된다", example = "단순 변심")
                                 @Size(max = 100, message = "취소 사유는 100자를 넘을 수 없습니다.")
                                 String reason) {
}
