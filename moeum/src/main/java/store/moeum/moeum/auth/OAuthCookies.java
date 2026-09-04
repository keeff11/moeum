package store.moeum.moeum.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.auth.AllowedOrigins;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * OAuth 왕복 동안만 쓰는 임시 쿠키. 전부 httpOnly 다.
 *
 * state 는 CSRF 방어용이라 브라우저가 읽을 이유가 없고, returnTo 는 열린 리다이렉트의 입구라
 * 값을 그대로 믿지 않고 콜백에서 허용 출처인지 다시 검증한다.
 */
@Component
public class OAuthCookies {

	public static final String STATE = "moeum_oauth_state";
	public static final String RETURN_TO = "moeum_oauth_return_to";

	/** 동의 화면에 머무는 시간만 살아 있으면 된다 */
	private static final Duration TTL = Duration.ofMinutes(5);
	private static final String DEFAULT_RETURN_TO = "/";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final boolean secure;
	private final AllowedOrigins allowedOrigins;

	public OAuthCookies(@Value("${moeum.auth.cookie-secure:false}") boolean secure,
	                    @Value("${moeum.auth.allowed-origins:}") List<String> allowedOrigins) {
		this.secure = secure;
		this.allowedOrigins = AllowedOrigins.of(allowedOrigins);
	}

	public String newState() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public void issue(HttpServletResponse response, String state, String returnTo) {
		add(response, STATE, state, TTL);
		// 쿠키 값에는 세미콜론 · 쉼표 · 공백을 그대로 넣을 수 없다. 경로에 쿼리가 붙는 경우를 대비해 인코딩한다
		add(response, RETURN_TO,
				URLEncoder.encode(sanitizeReturnTo(returnTo), StandardCharsets.UTF_8), TTL);
	}

	/** 쿠키의 returnTo 를 디코딩하고 다시 검증해서 돌려준다 */
	public String readReturnTo(HttpServletRequest request) {
		return read(request, RETURN_TO)
				.map(value -> {
					try {
						return URLDecoder.decode(value, StandardCharsets.UTF_8);
					} catch (IllegalArgumentException e) {
						return null;
					}
				})
				.map(this::sanitizeReturnTo)
				.orElseGet(this::defaultReturnTo);
	}

	public void clear(HttpServletResponse response) {
		add(response, STATE, "", Duration.ZERO);
		add(response, RETURN_TO, "", Duration.ZERO);
	}

	public Optional<String> read(HttpServletRequest request, String name) {
		if (request.getCookies() == null) {
			return Optional.empty();
		}
		return Arrays.stream(request.getCookies())
				.filter(cookie -> name.equals(cookie.getName()))
				.map(Cookie::getValue)
				.filter(value -> value != null && !value.isBlank())
				.findFirst();
	}

	/**
	 * 로그인 후 돌아갈 곳을 정한다.
	 *
	 * 프론트(www · studio.moeum.store)와 API(api.moeum.store)가 다른 호스트다.
	 * 상대 경로만 허용하면 API 호스트로 302 를 보내게 되고, 거기엔 화면이 없다.
	 * 그래서 **허용 출처 목록에 있는 절대 URL 이면 그대로 쓴다.**
	 * 판매자는 studio 로, 구매자는 www 로 각자 돌아가야 하므로 목록 대조가 필요하다.
	 *
	 * 허용 목록이 비어 있으면(로컬 · 테스트) 프론트와 API 가 같은 출처라는 뜻이라
	 * 예전처럼 상대 경로만 허용한다.
	 *
	 * "//evil.com" 은 브라우저가 프로토콜 상대 URL 로 읽어 외부로 나가고,
	 * 역슬래시는 일부 브라우저가 "/" 로 정규화하므로 같이 막는다.
	 */
	public String sanitizeReturnTo(String returnTo) {
		String fallback = defaultReturnTo();
		if (returnTo == null || returnTo.isBlank()) {
			return fallback;
		}
		String value = returnTo.trim();
		if (value.contains("\\") || value.startsWith("//")) {
			return fallback;
		}
		if (value.startsWith("/")) {
			return allowedOrigins.isEmpty() ? value : allowedOrigins.primary() + value;
		}
		return allowedOrigins.contains(originOf(value)) ? value : fallback;
	}

	/** 절대 URL 에서 스킴·호스트·포트만 꺼낸다. 파싱이 안 되면 null 이라 대조에서 걸러진다 */
	private String originOf(String url) {
		try {
			URI uri = new URI(url);
			return (uri.getScheme() == null || uri.getHost() == null)
					? null
					: uri.getScheme() + "://" + uri.getAuthority();
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private String defaultReturnTo() {
		String primary = allowedOrigins.primary();
		return (primary == null) ? DEFAULT_RETURN_TO : primary + DEFAULT_RETURN_TO;
	}

	private void add(HttpServletResponse response, String name, String value, Duration maxAge) {
		ResponseCookie cookie = ResponseCookie.from(name, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/")
				.maxAge(maxAge)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}
}
