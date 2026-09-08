package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

		@Schema(description = "결제창에서 돌아올 때 받은 세션 id. 서버가 저장해 둔 값과 대조한다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "sessionId 는 필수입니다")
		String sessionId,

		/**
		 * successUrl 쿼리로 받은 결제자 식별값. 없어도 결제는 된다.
		 *
		 * 다음 결제(2차금)에서 인증 단계를 줄이는 용도라, 받은 문자열을 그대로 넘긴다 —
		 * 접두사를 떼거나 대소문자를 바꾸면 인증 생략이 동작하지 않는다 (point3-api 5절).
		 */
		@Schema(description = "successUrl 쿼리로 받은 결제자 식별값. 없어도 결제는 된다. "
				+ "받은 문자열을 그대로 넘긴다 — 접두사를 떼거나 대소문자를 바꾸면 다음 결제의 인증 생략이 동작하지 않는다")
		String payerId
) {
}
