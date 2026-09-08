package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 결제창을 띄우는 데 필요한 값 (payment-flow 13번).
 *
 * @param sessionId  point3 세션. SDK 의 requestPayment 에 넘긴다
 * @param orderToken 복귀 페이지 주소에 쓰는 우리 토큰. 세션 토큰과 다른 값이다
 * @param amount     청구액. <b>표시용이다</b> — 검증은 서버가 DB 값으로 한다 (CLAUDE.md 규칙 5)
 * @param clientId   브라우저용 point3 키. 서버 API 토큰과 완전히 별개고 공개돼도 되는 값이다
 * @param payerId    저장해 둔 결제자 식별값. SDK 의 customerKey 로 넘긴다.
 *                   없으면 null 이고 프론트는 'ANONYMOUS' 를 쓴다 (point3-api 6절)
 */
public record PaySessionResponse(
		@Schema(description = "point3 결제창에 넘길 세션 id. SDK 에 그대로 전달한다")
		String sessionId,

		@Schema(description = "이 주문을 가리키는 토큰. 승인·상태조회·취소에서 계속 쓴다")
		String orderToken,

		@Schema(description = "이번에 청구되는 금액(원). 서버가 DB 값으로 계산한 것이라 그대로 보여주면 된다",
				example = "60000")
		int amount,

		@Schema(description = "point3 브라우저용 클라이언트 키. SDK 초기화에 쓴다")
		String clientId,

		@Schema(description = "저장된 결제자 식별값. SDK 의 customerKey 로 넘기면 인증 단계가 줄어든다. "
				+ "없으면 null 이고, 그때는 그냥 진행하면 된다")
		String payerId
) {
}
