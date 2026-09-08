package store.moeum.moeum.payment.refund.dto;

import jakarta.validation.constraints.Size;

/**
 * 취소 요청 본문.
 *
 * <b>금액을 받지 않는다.</b> 얼마를 돌려줄지는 서버가 DB 의 주문 금액에서 계산한다
 * (CLAUDE.md 규칙 9). 클라이언트가 보낸 금액을 그대로 취소하면 조작할 수 있다.
 *
 * @param orderId 폼 하나만 취소하면 그 주문 id. null 이면 남은 폼 전부
 */
public record OrderRefundRequest(Long orderId,
                                 @Size(max = 100, message = "취소 사유는 100자를 넘을 수 없습니다.")
                                 String reason) {
}
