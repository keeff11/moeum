package store.moeum.moeum.seller.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 셀러 온보딩 제출. 제출하면 review_status = PENDING 으로 심사 대기에 들어간다 */
public record OnboardingRequest(

		@NotBlank(message = "판매공간 주소는 필수입니다")
		@Size(max = 64, message = "64자를 넘을 수 없습니다")
		@Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "영소문자·숫자·하이픈만 쓸 수 있습니다")
		String storeSlug,

		@NotBlank(message = "사업자번호는 필수입니다")
		@Pattern(regexp = "^[0-9]{10}$", message = "숫자 10자리여야 합니다")
		String businessNo,

		@NotBlank(message = "정산계좌는 필수입니다")
		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String settlementAccount,

		@NotBlank(message = "대표자명은 필수입니다")
		@Size(max = 50, message = "50자를 넘을 수 없습니다")
		String representativeName,

		@NotBlank(message = "연락처는 필수입니다")
		@Pattern(regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$", message = "휴대폰 번호 형식이 아닙니다")
		String phone,

		@NotBlank(message = "이메일은 필수입니다")
		@Email(message = "이메일 형식이 아닙니다")
		@Size(max = 120, message = "120자를 넘을 수 없습니다")
		String email,

		/** 주문 묶음당 1회 부과 */
		@Min(value = 0, message = "0 이상이어야 합니다")
		int shippingFee,

		/** 이 금액 이상 무료배송. null 이면 미적용 */
		@Min(value = 0, message = "0 이상이어야 합니다")
		Integer freeShippingOver
) {
}
