package store.moeum.moeum.order.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 스마트택배(스윗트래커) 배송조회 설정.
 *
 * <b>키가 없으면 조회 기능만 꺼진다.</b> 앱은 그대로 뜬다 — S3 · point3 · SOLAPI 와 같은 규칙이다.
 * 배송조회는 부가 기능이라, 키가 없다고 송장 등록이나 주문이 막히면 안 된다.
 *
 * <b>키와 별개로 {@code enabled} 스위치를 둔다.</b> 무료 이용권이 월 100건이라 켤 시점을
 * 고르고 싶은데, 키를 넣고 빼는 것으로 끄고 켜면 급할 때 번거롭고 키를 다시 찾아야 한다.
 * <b>기본값이 false 다</b> — 켜는 것은 명시적인 행동이어야 한다. 키만 넣어 두면 아직 안 나간다.
 *
 * @param enabled        실제로 조회를 부를지. <b>기본값 false</b>. 키가 있어도 이게 켜져야 나간다
 * @param baseUrl        기본값이 운영 주소다. WireMock 으로 갈아 끼우려고 설정에 둔다
 * @param apiKey         발급받은 t_key. <b>저장소에 두지 않는다</b> — Parameter Store 에서 온다 (D-017)
 * @param connectTimeout 연결 타임아웃
 * @param readTimeout    읽기 타임아웃. 배송조회는 화면이 기다리는 호출이라 짧게 둔다
 * @param carrierCacheTtl  택배사 목록 캐시. 택배사는 거의 안 늘어난다
 * @param trackingCacheTtl 진행 중인 배송의 조회 캐시. 새로고침 연타를 흡수한다
 * @param failureCacheTtl  조회 실패 캐시. <b>짧게 둔다</b> — 송장 등록 직후에는 택배사가
 *                         아직 인식하지 못해 실패하는데, 길게 잡으면 그동안 계속 실패로 보인다
 */
@ConfigurationProperties(prefix = "moeum.tracking")
public record SmartTrackerProperties(
		Boolean enabled,
		String baseUrl,
		String apiKey,
		Duration connectTimeout,
		Duration readTimeout,
		Duration carrierCacheTtl,
		Duration trackingCacheTtl,
		Duration failureCacheTtl
) {

	public SmartTrackerProperties {
		// 켜는 것은 명시적인 행동이어야 한다. 설정에 없으면 꺼진 것으로 본다
		enabled = (enabled != null) && enabled;
		baseUrl = (baseUrl == null || baseUrl.isBlank())
				? "https://info.sweettracker.co.kr" : trimSlash(baseUrl);
		connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(2) : connectTimeout;
		readTimeout = (readTimeout == null) ? Duration.ofSeconds(5) : readTimeout;
		carrierCacheTtl = (carrierCacheTtl == null) ? Duration.ofDays(1) : carrierCacheTtl;
		trackingCacheTtl = (trackingCacheTtl == null) ? Duration.ofMinutes(30) : trackingCacheTtl;
		failureCacheTtl = (failureCacheTtl == null) ? Duration.ofMinutes(5) : failureCacheTtl;
	}

	/** 키가 채워져 있는가. 켜져 있는지와는 별개다 */
	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}

	/** 실제로 조회를 부를 수 있는가. 키와 스위치가 <b>둘 다</b> 있어야 한다 */
	public boolean isEnabled() {
		return enabled && isConfigured();
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
