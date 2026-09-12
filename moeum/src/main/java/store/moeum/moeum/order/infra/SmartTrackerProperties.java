package store.moeum.moeum.order.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 스마트택배(스윗트래커) 배송조회 설정.
 *
 * <b>키가 없으면 조회 기능만 꺼진다.</b> 앱은 그대로 뜬다 — S3 · point3 · SOLAPI 와 같은 규칙이다.
 * 배송조회는 부가 기능이라, 키가 없다고 송장 등록이나 주문이 막히면 안 된다.
 *
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
		String baseUrl,
		String apiKey,
		Duration connectTimeout,
		Duration readTimeout,
		Duration carrierCacheTtl,
		Duration trackingCacheTtl,
		Duration failureCacheTtl
) {

	public SmartTrackerProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank())
				? "https://info.sweettracker.co.kr" : trimSlash(baseUrl);
		connectTimeout = (connectTimeout == null) ? Duration.ofSeconds(2) : connectTimeout;
		readTimeout = (readTimeout == null) ? Duration.ofSeconds(5) : readTimeout;
		carrierCacheTtl = (carrierCacheTtl == null) ? Duration.ofDays(1) : carrierCacheTtl;
		trackingCacheTtl = (trackingCacheTtl == null) ? Duration.ofMinutes(30) : trackingCacheTtl;
		failureCacheTtl = (failureCacheTtl == null) ? Duration.ofMinutes(5) : failureCacheTtl;
	}

	public boolean isConfigured() {
		return apiKey != null && !apiKey.isBlank();
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
