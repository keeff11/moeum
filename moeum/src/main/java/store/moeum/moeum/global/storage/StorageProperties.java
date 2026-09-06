package store.moeum.moeum.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 이미지 저장소(S3) 설정.
 *
 * 값이 비어 있으면 업로드 기능만 꺼진다 — 앱은 그대로 뜬다.
 * 로컬 개발에서 AWS 자격증명을 갖추게 만들 이유가 없다.
 *
 * @param bucket        S3 버킷 이름. 비면 업로드 비활성
 * @param publicBaseUrl 이미지를 읽는 쪽 주소. 나중에 CloudFront 도메인으로 바꾸면 여기만 고친다
 * @param keyPrefix     업로드 키 앞에 붙는 경로
 * @param uploadUrlTtl  발급한 URL 의 유효 시간. 짧게 둔다 — 새어 나가도 그 시간만 쓸 수 있다
 * @param maxUploadSize 허용 최대 크기. 서명에 Content-Length 를 넣어 강제한다
 */
@ConfigurationProperties(prefix = "moeum.storage")
public record StorageProperties(
		String bucket,
		String publicBaseUrl,
		String keyPrefix,
		Duration uploadUrlTtl,
		Long maxUploadSize
) {

	public StorageProperties {
		keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "sale-forms" : trimSlash(keyPrefix);
		uploadUrlTtl = (uploadUrlTtl == null) ? Duration.ofMinutes(5) : uploadUrlTtl;
		maxUploadSize = (maxUploadSize == null) ? 10L * 1024 * 1024 : maxUploadSize;
		publicBaseUrl = (publicBaseUrl == null) ? null : trimSlash(publicBaseUrl);
	}

	public boolean isConfigured() {
		return bucket != null && !bucket.isBlank();
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}
}
