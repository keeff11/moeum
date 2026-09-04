package store.moeum.moeum.auth.infra;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 토큰 응답. 이 값은 서버 밖으로 나가지 않는다.
 * toString 을 만들지 않는다 — 로그에 토큰이 실리면 안 된다.
 */
public record KakaoTokens(
		@JsonProperty("access_token") String accessToken,
		@JsonProperty("refresh_token") String refreshToken,
		@JsonProperty("expires_in") Integer expiresIn
) {

	@Override
	public String toString() {
		return "KakaoTokens(masked)";
	}
}
