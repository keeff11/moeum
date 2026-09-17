package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 인증번호 확인 (D-064) */
public record PhoneVerificationConfirmRequest(

		@Schema(description = "인증번호를 요청한 그 번호. 다르면 인증되지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "010-1234-5678")
		@NotBlank(message = "휴대폰 번호는 필수입니다")
		@Pattern(regexp = PhonePattern.MOBILE, message = "휴대폰 번호 형식이 아닙니다")
		String phone,

		@Schema(description = "문자로 받은 6자리 숫자", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "123456")
		@NotBlank(message = "인증번호는 필수입니다")
		@Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 숫자 6자리입니다")
		String code
) {
}
