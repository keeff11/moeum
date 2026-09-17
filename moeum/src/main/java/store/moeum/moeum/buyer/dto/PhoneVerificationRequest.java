package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 인증번호 요청 (D-064) */
public record PhoneVerificationRequest(

		@Schema(description = "알림 받을 휴대폰 번호. 하이픈은 있어도 없어도 된다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "010-1234-5678")
		@NotBlank(message = "휴대폰 번호는 필수입니다")
		@Pattern(regexp = PhonePattern.MOBILE, message = "휴대폰 번호 형식이 아닙니다")
		String phone
) {
}
