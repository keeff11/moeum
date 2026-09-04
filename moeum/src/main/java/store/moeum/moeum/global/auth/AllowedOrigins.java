package store.moeum.moeum.global.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * 믿는 프론트 출처 목록.
 *
 * CORS 허용 · Origin 검증 · 로그인 후 returnTo 판정이 **같은 목록 하나**를 본다.
 * 셋이 갈라지면 "CORS 는 통과했는데 필터가 막는" 식으로 어긋난다.
 *
 * 스프링 빈으로 두지 않는다. WebConfig 가 다른 빈에 의존하면
 * {@code @WebMvcTest} 같은 슬라이스에서 컨텍스트가 뜨지 않는다.
 */
public final class AllowedOrigins {

	private final List<String> origins;

	private AllowedOrigins(List<String> origins) {
		this.origins = origins;
	}

	public static AllowedOrigins of(List<String> configured) {
		if (configured == null) {
			return new AllowedOrigins(List.of());
		}
		return new AllowedOrigins(configured.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(AllowedOrigins::normalize)
				.filter(value -> value != null)
				.distinct()
				.toList());
	}

	/** CORS 설정과 Origin 검증이 쓰는 목록 */
	public List<String> list() {
		return origins;
	}

	public boolean isEmpty() {
		return origins.isEmpty();
	}

	public boolean contains(String origin) {
		String normalized = normalize(origin);
		return normalized != null && origins.contains(normalized);
	}

	/**
	 * 기본 복귀 지점. 목록의 첫 항목이다.
	 * 목록이 비어 있으면 null — 로컬처럼 프론트와 API 가 같은 출처인 경우다.
	 */
	public String primary() {
		return origins.isEmpty() ? null : origins.get(0);
	}

	/**
	 * 스킴 · 호스트 · 포트만 남기고 소문자로 맞춘다.
	 *
	 * 브라우저가 보내는 Origin 헤더가 이 형태라 비교 기준을 여기에 맞춘다.
	 * 기본 포트(80 · 443)는 Origin 헤더에 붙지 않으므로 떼어낸다.
	 */
	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = new URI(value.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null) {
				return null;
			}
			scheme = scheme.toLowerCase(Locale.ROOT);
			host = host.toLowerCase(Locale.ROOT);

			int port = uri.getPort();
			boolean defaultPort = (port == -1)
					|| ("http".equals(scheme) && port == 80)
					|| ("https".equals(scheme) && port == 443);

			return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
		} catch (URISyntaxException e) {
			return null;
		}
	}
}
