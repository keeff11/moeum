package store.moeum.moeum.global.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

	/** DeleteObjects 한 번에 보낼 수 있는 최대 키 수. S3 가 정한 값이다 */
	private static final int DELETE_BATCH_SIZE = 1000;

	private final StorageProperties properties;
	private final S3Presigner presigner;
	private final S3Client s3Client;

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
	 * 이 키가 그 소유자가 발급받은 것인가.
	 *
	 * 키는 {@code {prefix}/{ownerId}/{uuid}.{ext}} 라 접두사만 봐도 판별된다.
	 * <b>클라이언트가 키를 그대로 보내오는 경로에서는 반드시 확인한다</b> —
	 * 남의 키를 넣으면 남의 이미지를 자기 것으로 걸어 둘 수 있다.
	 */
	public boolean ownsKey(Long ownerId, String objectKey) {
		if (objectKey == null || objectKey.isBlank()) {
			return true;   // 지우는 것은 언제나 허용한다
		}
		return objectKey.startsWith("%s/%d/".formatted(properties.keyPrefix(), ownerId));
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

	/**
	 * 버킷에 실제로 있는 객체를 전부 훑는다. 고아 파일 청소가 쓴다.
	 *
	 * 한 번에 1000개씩 끊어 오므로 paginator 에 맡긴다. 접두사 안쪽만 본다 —
	 * 같은 버킷에 다른 용도의 경로가 생겨도 청소 대상이 되지 않는다.
	 *
	 * 버킷이 설정되지 않은 환경(로컬)에서는 빈 목록이다. 호출한 쪽이 그대로 아무 일도 안 하게 된다.
	 */
	public List<StoredObject> listAll() {
		if (!properties.isConfigured()) {
			return List.of();
		}

		ListObjectsV2Request request = ListObjectsV2Request.builder()
				.bucket(properties.bucket())
				.prefix(properties.keyPrefix() + "/")
				.build();

		List<StoredObject> objects = new ArrayList<>();
		s3Client.listObjectsV2Paginator(request)
				.contents()
				.forEach(object -> objects.add(new StoredObject(object.key(), object.lastModified())));
		return objects;
	}

	/**
	 * 객체를 지운다. <b>되돌릴 수 없다</b> — 버킷 versioning 이 켜져 있어야 복구가 가능하다.
	 *
	 * 1000개씩 묶어 보낸다. 하나씩 부르면 호출 수가 그대로 건수가 되고 요금도 그만큼 붙는다.
	 * 일부만 실패해도 나머지는 지워진다 — 남은 것은 다음 회차가 다시 집는다.
	 *
	 * @return 실제로 지워진 건수
	 */
	public int deleteAll(List<String> objectKeys) {
		if (!properties.isConfigured() || objectKeys.isEmpty()) {
			return 0;
		}

		int deleted = 0;
		for (int from = 0; from < objectKeys.size(); from += DELETE_BATCH_SIZE) {
			List<String> chunk = objectKeys.subList(
					from, Math.min(from + DELETE_BATCH_SIZE, objectKeys.size()));

			DeleteObjectsResponse response = s3Client.deleteObjects(DeleteObjectsRequest.builder()
					.bucket(properties.bucket())
					.delete(Delete.builder()
							.objects(chunk.stream()
									.map(key -> ObjectIdentifier.builder().key(key).build())
									.toList())
							.build())
					.build());

			deleted += response.deleted().size();
			response.errors().forEach(error ->
					log.warn("이미지 삭제 실패: key={}, code={}, message={}",
							error.key(), error.code(), error.message()));
		}
		return deleted;
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

	/**
	 * 버킷에 있는 객체 하나.
	 *
	 * @param key          S3 객체 키
	 * @param lastModified 올라온 시각. <b>고아 판정의 유예 기간이 이 값을 기준으로 잡힌다</b> —
	 *                     방금 올린 이미지는 아직 폼 저장을 기다리는 중일 수 있다
	 */
	public record StoredObject(String key, Instant lastModified) {
	}
}
