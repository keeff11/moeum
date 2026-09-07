package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 세션 생성 · 조회 응답.
 *
 * {@code id} 가 sessionId 이고 이후 모든 호출의 경로 파라미터가 된다.
 * 형식은 {@code pymt_sess-} + UUID v7 인데 <b>파싱해서 의미를 꺼내지 않는다</b> (point3-api 10절).
 * 서명이 없어 값 자체로는 진위를 못 가린다 — 우리 DB 에 저장한 값과 문자열 비교하거나 point3 에 조회한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Point3Session(
		String id,
		Point3SessionStatus status,
		Integer amount,
		Integer supplyAmount,
		Integer vat,
		Integer taxFreeAmount,
		String currency
) {
}
