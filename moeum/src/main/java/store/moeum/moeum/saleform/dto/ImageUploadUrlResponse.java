package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.global.storage.ImageStorage;

/**
 * 발급된 업로드 URL.
 *
 * 프론트는 {@code uploadUrl} 로 파일을 PUT 하고, 성공하면 {@code objectKey} 를
 * 판매 폼 생성·수정 요청의 {@code images[]} 에 실어 보낸다.
 * 폼을 저장하지 않으면 올라간 파일은 아무 데서도 참조되지 않는다.
 *
 * @param uploadUrl         이 주소로 PUT 한다
 * @param objectKey         폼 저장 때 보낼 값
 * @param contentType       PUT 의 Content-Type 헤더에 그대로 넣어야 한다. 다르면 서명이 어긋난다
 * @param expiresInSeconds  남은 유효 시간. 지나면 다시 발급받는다
 */
public record ImageUploadUrlResponse(
		@Schema(description = "이 주소로 브라우저가 직접 PUT 한다. 서버를 거치지 않는다")
		String uploadUrl,

		@Schema(description = "업로드 후 판매 폼·프로필에 저장할 키. 이 값을 그대로 넘긴다",
				example = "sale-forms/1/9f3a....jpg")
		String objectKey,

		@Schema(description = "PUT 할 때 Content-Type 헤더에 그대로 넣어야 하는 값. "
				+ "다르면 서명이 맞지 않아 거부된다", example = "image/jpeg")
		String contentType,

		@Schema(description = "이 주소가 유효한 시간(초). 지나면 다시 발급받아야 한다", example = "300")
		long expiresInSeconds
) {

	public static ImageUploadUrlResponse from(ImageStorage.PresignedUpload upload) {
		return new ImageUploadUrlResponse(
				upload.url(), upload.objectKey(), upload.contentType(), upload.expiresInSeconds());
	}
}
