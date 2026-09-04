package store.moeum.moeum.auth.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.nio.charset.StandardCharsets;

/**
 * 카카오 OAuth 호출. 타임아웃은 필수다 — 응답이 늦어지면 요청 스레드가 그만큼 잡힌다.
 *
 * 4xx 와 5xx 를 나눠서 잡는다. 4xx 는 code 만료·재사용처럼 되돌려도 안전한 확정 실패고,
 * 5xx·타임아웃은 카카오 쪽 문제라 사용자에게 다른 안내를 해야 한다.
 * 결제와 달리 여기서는 되돌릴 상태가 없어 둘 다 로그인 실패로 끝내지만, 로그는 구분해 남긴다.
 */
@Slf4j
@Component
public class KakaoOAuthClient {

	private final KakaoOAuthProperties properties;
	private final RestClient restClient;

	public KakaoOAuthClient(KakaoOAuthProperties properties) {
		this.properties = properties;

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());
		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	/** 동의 화면 URL. state 는 호출자가 발급해 쿠키에 넣어 둔다 */
	public String authorizeUrl(String state) {
		return UriComponentsBuilder.fromUriString(properties.authorizeUri())
				.queryParam("response_type", "code")
				.queryParam("client_id", properties.clientId())
				.queryParam("redirect_uri", properties.redirectUri())
				.queryParam("state", state)
				.build(true)
				.toUriString();
	}

	public KakaoTokens exchangeToken(String code) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", "authorization_code");
		form.add("client_id", properties.clientId());
		form.add("redirect_uri", properties.redirectUri());
		form.add("code", code);
		if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
			form.add("client_secret", properties.clientSecret());
		}

		try {
			return restClient.post()
					.uri(properties.tokenUri())
					.contentType(new MediaType(MediaType.APPLICATION_FORM_URLENCODED, StandardCharsets.UTF_8))
					.body(form)
					.retrieve()
					.onStatus(status -> status.is4xxClientError(), (request, response) -> {
						log.warn("카카오 토큰 교환 4xx: status={}", response.getStatusCode());
						throw new BusinessException(ErrorCode.OAUTH_STATE_MISMATCH,
								"인가 코드가 만료되었거나 이미 사용되었습니다. 다시 로그인해 주세요.");
					})
					.onStatus(status -> status.is5xxServerError(), (request, response) -> {
						log.error("카카오 토큰 교환 5xx: status={}", response.getStatusCode());
						throw new BusinessException(ErrorCode.OAUTH_FAILED);
					})
					.body(KakaoTokens.class);
		} catch (BusinessException e) {
			throw e;
		} catch (RestClientException e) {
			log.error("카카오 토큰 교환 실패: {}", e.getClass().getSimpleName());
			throw new BusinessException(ErrorCode.OAUTH_FAILED);
		}
	}

	public KakaoProfile fetchProfile(String accessToken) {
		try {
			return restClient.get()
					.uri(properties.userInfoUri())
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.onStatus(status -> status.is4xxClientError(), (request, response) -> {
						log.warn("카카오 프로필 조회 4xx: status={}", response.getStatusCode());
						throw new BusinessException(ErrorCode.OAUTH_FAILED);
					})
					.onStatus(status -> status.is5xxServerError(), (request, response) -> {
						log.error("카카오 프로필 조회 5xx: status={}", response.getStatusCode());
						throw new BusinessException(ErrorCode.OAUTH_FAILED);
					})
					.body(KakaoProfile.class);
		} catch (BusinessException e) {
			throw e;
		} catch (RestClientException e) {
			log.error("카카오 프로필 조회 실패: {}", e.getClass().getSimpleName());
			throw new BusinessException(ErrorCode.OAUTH_FAILED);
		}
	}
}
