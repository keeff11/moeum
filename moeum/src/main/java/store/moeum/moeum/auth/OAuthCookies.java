package store.moeum.moeum.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * OAuth 왕복 동안만 쓰는 임시 쿠키. 전부 httpOnly 다.
 *
 * state 는 CSRF 방어용이라 브라우저가 읽을 이유가 없고, returnTo 는 열린 리다이렉트의 입구라
 * 값을 그대로 믿지 않고 콜백에서 상대 경로인지 다시 검증한다.
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

	public OAuthCookies(@Value("${moeum.auth.cookie-secure:false}") boolean secure) {
		this.secure = secure;
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
				.orElse(DEFAULT_RETURN_TO);
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
	 * 우리 사이트 안의 상대 경로만 허용한다.
	 * "//evil.com" 은 브라우저가 프로토콜 상대 URL 로 읽어 외부로 나가고,
	 * 역슬래시는 일부 브라우저가 "/" 로 정규화하므로 같이 막는다.
	 */
	public String sanitizeReturnTo(String returnTo) {
		if (returnTo == null || returnTo.isBlank()) {
			return DEFAULT_RETURN_TO;
		}
		if (!returnTo.startsWith("/")) {
			return DEFAULT_RETURN_TO;
		}
		if (returnTo.startsWith("//") || returnTo.contains("\\")) {
			return DEFAULT_RETURN_TO;
		}
		return returnTo;
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
