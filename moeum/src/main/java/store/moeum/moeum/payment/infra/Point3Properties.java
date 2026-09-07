package store.moeum.moeum.payment.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * point3 연동 설정.
 *
 * <b>apiToken 은 서버 전용 비밀값이다.</b> 브라우저에서 쓰는 clientId 와 완전히 다른 값이고,
 * URL · 로그 · 응답 어디에도 실리면 안 된다 (point3-api 9절).
 *
 * 토큰이 비어 있으면 앱은 뜨되 결제 호출만 막힌다. 발급 전에 배포가 죽으면 안 된다.
 *
 * @param connectTimeout 연결 타임아웃. 짧게 — 상대가 안 받으면 빨리 포기한다
 * @param readTimeout    응답 타임아웃. <b>이게 없으면 요청 스레드가 무한정 잡힌다</b>
 */
@ConfigurationProperties(prefix = "moeum.point3")
public record Point3Properties(
		String baseUrl,
		String apiToken,
		/** 브라우저용 키. 공개돼도 되는 값이라 프론트에 그대로 내려준다 */
		String clientId,
		Duration connectTimeout,
		Duration readTimeout
) {

	public Point3Properties {
		clientId = (clientId == null) ? "" : clientId;
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.point3.io" : trimSlash(baseUrl);
		connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(3) : connectTimeout;
		readTimeout = (readTimeout == null) ? Duration.ofSeconds(10) : readTimeout;
	}

	public boolean isConfigured() {
		return apiToken != null && !apiToken.isBlank();
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
