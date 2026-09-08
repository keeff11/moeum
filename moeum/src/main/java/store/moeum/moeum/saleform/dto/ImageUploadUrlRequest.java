package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 이미지 업로드 URL 발급 요청.
 *
 * 파일 이름을 받지 않는다 — 키는 서버가 만든다. 클라이언트가 정하게 두면
 * 남의 경로를 지정해 덮어쓸 수 있다.
 *
 * @param contentType   업로드할 파일의 MIME 타입. 서명에 들어가므로 PUT 할 때 그대로 보내야 한다
 * @param contentLength 정확한 바이트 수. 서명된 값과 다르면 S3 가 거부한다 — 크기 상한이 이걸로 강제된다
 */
public record ImageUploadUrlRequest(

		@Schema(description = "올릴 파일의 MIME 타입. image/jpeg · image/png · image/webp 만 받는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "image/jpeg")
		@NotBlank(message = "contentType 은 필수입니다")
		String contentType,

		@Schema(description = "올릴 파일의 정확한 바이트 수. 서명에 포함되므로 실제 크기와 다르면 업로드가 거부된다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "204800")
		@Min(value = 1, message = "1 이상이어야 합니다")
		long contentLength
) {
}
