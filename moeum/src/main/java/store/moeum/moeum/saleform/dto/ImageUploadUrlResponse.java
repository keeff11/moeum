package store.moeum.moeum.saleform.dto;

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
		String uploadUrl,
		String objectKey,
		String contentType,
		long expiresInSeconds
) {

	public static ImageUploadUrlResponse from(ImageStorage.PresignedUpload upload) {
		return new ImageUploadUrlResponse(
				upload.url(), upload.objectKey(), upload.contentType(), upload.expiresInSeconds());
	}
}
