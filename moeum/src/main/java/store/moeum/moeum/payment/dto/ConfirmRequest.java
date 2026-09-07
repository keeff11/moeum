package store.moeum.moeum.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 복귀 페이지가 전달하는 승인 요청 (payment-flow 17번).
 *
 * <b>금액을 받지 않는다.</b> 프론트가 보낸 금액이나 successUrl 쿼리의 값을 검증 기준으로
 * 쓰지 않는다 (CLAUDE.md 규칙 5). 청구액은 서버가 DB 에서 읽는다.
 *
 * sessionId 도 신뢰해서 쓰는 게 아니라, 우리가 저장해 둔 값과 같은지 대조하는 용도다.
 */
public record ConfirmRequest(

		@NotBlank(message = "sessionId 는 필수입니다")
		String sessionId
) {
}
