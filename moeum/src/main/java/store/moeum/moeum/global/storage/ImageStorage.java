package store.moeum.moeum.global.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 상품 이미지 저장소. <b>파일 바이트는 이 서버를 지나가지 않는다.</b>
 *
 * 서버는 presigned URL 만 발급하고 브라우저가 S3 로 직접 올린다.
 * t3.small 한 대에 JVM 과 MySQL 이 같이 사는 구성이라 이미지를 중계하면
 * 동시 업로드 몇 개에 메모리가 마르고, 대역폭도 EC2 를 두 번 탄다.
 *
 * 대신 서버가 바이트를 못 보므로 검증할 지점이 발급 시점밖에 없다. 그래서 셋을 서명에 넣는다.
 *  - <b>키</b> — 서버가 만든다. 클라이언트가 정하게 두면 남의 경로에 덮어쓸 수 있다
 *  - <b>Content-Type</b> — 허용 목록 밖이면 발급하지 않는다
 *  - <b>Content-Length</b> — 서명된 값과 다르면 S3 가 거부한다. 크기 상한이 실제로 강제된다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageStorage {

	/** 허용 이미지 형식과 확장자 */
	private static final Map<String, String> ALLOWED_TYPES = Map.of(
			"image/jpeg", "jpg",
			"image/png", "png",
			"image/webp", "webp",
			"image/gif", "gif"
	);

	private final StorageProperties properties;
	private final S3Presigner presigner;

	/**
	 * 업로드용 presigned PUT URL 을 발급한다.
	 *
	 * @param ownerId       키를 나누는 기준. 셀러 id 다
	 * @param contentType   업로드할 파일의 MIME 타입
	 * @param contentLength 업로드할 파일의 정확한 바이트 수
	 */
	public PresignedUpload presignUpload(Long ownerId, String contentType, long contentLength) {
		if (!properties.isConfigured()) {
			// 로컬처럼 버킷이 없는 환경이다. 500 으로 터뜨리지 않고 기능만 꺼진 것으로 알린다
			throw new BusinessException(ErrorCode.STORAGE_NOT_CONFIGURED);
		}

		String extension = extensionOf(contentType);
		if (contentLength <= 0 || contentLength > properties.maxUploadSize()) {
			throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
		}

		String key = "%s/%d/%s.%s".formatted(
				properties.keyPrefix(), ownerId, UUID.randomUUID(), extension);

		PutObjectRequest put = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.contentType(contentType)
				.contentLength(contentLength)
				.build();

		Duration ttl = properties.uploadUrlTtl();
		String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
						.signatureDuration(ttl)
						.putObjectRequest(put)
						.build())
				.url()
				.toString();

		log.info("이미지 업로드 URL 발급: ownerId={}, key={}, size={}", ownerId, key, contentLength);
		return new PresignedUpload(url, key, contentType, ttl.getSeconds());
	}

	/**
	 * 저장된 키를 읽기용 주소로 조립한다.
	 *
	 * 키만 저장하는 이유가 이 메서드다. 버킷을 바꾸거나 CloudFront 를 앞에 세워도
	 * 설정 한 줄만 바뀌고, 이미 쌓인 행은 손대지 않는다.
	 */
	public String publicUrl(String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return null;
		}
		// 이미 절대 주소면 그대로 둔다 — 업로드가 붙기 전에 외부 URL 로 넣어 둔 값이 있을 수 있다
		if (objectKey.startsWith("http://") || objectKey.startsWith("https://")) {
			return objectKey;
		}
		String base = properties.publicBaseUrl();
		if (base == null || base.isBlank()) {
			return objectKey;
		}
		return base + "/" + objectKey;
	}

	private static String extensionOf(String contentType) {
		String normalized = (contentType == null) ? "" : contentType.trim().toLowerCase(Locale.ROOT);
		String extension = ALLOWED_TYPES.get(normalized);
		if (extension == null) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
		}
		return extension;
	}

	/**
	 * @param url          이 주소로 PUT 한다. 만료되면 다시 발급받아야 한다
	 * @param objectKey    업로드가 끝난 뒤 판매 폼 저장 요청의 images[] 에 실어 보낼 값
	 * @param contentType  PUT 할 때 이 헤더를 그대로 보내야 한다. 다르면 서명이 어긋난다
	 * @param expiresInSeconds 남은 유효 시간
	 */
	public record PresignedUpload(String url, String objectKey, String contentType, long expiresInSeconds) {
	}
}
