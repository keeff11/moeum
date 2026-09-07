package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 승인 응답 (point3-api 4절).
 *
 * <b>200 이 성공이 아니다.</b> {@code status} 가 {@code captured} 여야 출금된 것이고,
 * {@code processing} 이면 결과를 아직 모른다 — 되돌리지 말고 재조회한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Point3Capture(String id, Point3SessionStatus status) {

	/** 이것만이 성공이다 (CLAUDE.md 규칙 6) */
	public boolean isCaptured() {
		return status == Point3SessionStatus.CAPTURED;
	}
}
