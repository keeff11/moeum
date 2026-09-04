package store.moeum.moeum.auth.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 카카오 앱 등록 정보는 전부 서버가 갖는다 (api-spec 10-⑧). 프론트에 내려보내지 않는다.
 */
@ConfigurationProperties(prefix = "moeum.oauth.kakao")
public record KakaoOAuthProperties(
		String clientId,
		String clientSecret,
		String redirectUri,
		String authorizeUri,
		String tokenUri,
		String userInfoUri,
		Duration connectTimeout,
		Duration readTimeout
) {

	public KakaoOAuthProperties {
		authorizeUri = (authorizeUri == null) ? "https://kauth.kakao.com/oauth/authorize" : authorizeUri;
		tokenUri = (tokenUri == null) ? "https://kauth.kakao.com/oauth/token" : tokenUri;
		userInfoUri = (userInfoUri == null) ? "https://kapi.kakao.com/v2/user/me" : userInfoUri;
		connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(3) : connectTimeout;
		readTimeout = (readTimeout == null) ? Duration.ofSeconds(5) : readTimeout;
	}
}
