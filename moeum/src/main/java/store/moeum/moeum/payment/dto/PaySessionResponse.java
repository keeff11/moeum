package store.moeum.moeum.payment.dto;

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
		String sessionId,
		String orderToken,
		int amount,
		String clientId,
		String payerId
) {
}
