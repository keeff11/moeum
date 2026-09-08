package store.moeum.moeum.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 셀러 온보딩 제출. 제출하면 review_status = PENDING 으로 심사 대기에 들어간다 */
public record OnboardingRequest(

		@Schema(description = "판매공간 주소. meoum.store/{이 값} 이 된다. 영소문자·숫자·하이픈만",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "moeum-store")
		@NotBlank(message = "판매공간 주소는 필수입니다")
		@Size(max = 64, message = "64자를 넘을 수 없습니다")
		@Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "영소문자·숫자·하이픈만 쓸 수 있습니다")
		String storeSlug,

		/** 공개 상품 페이지에 표시되는 이름. 대표자 실명을 쓰지 않기 위해 따로 받는다 */
		@Schema(description = "공개 페이지에 표시되는 상호명. 대표자 실명을 쓰지 않으려고 따로 받는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "모음 상점")
		@NotBlank(message = "상호명은 필수입니다")
		@Size(max = 60, message = "60자를 넘을 수 없습니다")
		String storeName,

		@Schema(description = "사업자등록번호 10자리(하이픈 없이). 암호화해 저장하고 구매자에게 나가지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "1234567890")
		@NotBlank(message = "사업자번호는 필수입니다")
		@Pattern(regexp = "^[0-9]{10}$", message = "숫자 10자리여야 합니다")
		String businessNo,

		@Schema(description = "정산 받을 계좌. 암호화해 저장하고 구매자에게 나가지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "우리 1002-000-000000")
		@NotBlank(message = "정산계좌는 필수입니다")
		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String settlementAccount,

		@Schema(description = "대표자 실명. 심사용이라 구매자에게 나가지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "홍길동")
		@NotBlank(message = "대표자명은 필수입니다")
		@Size(max = 50, message = "50자를 넘을 수 없습니다")
		String representativeName,

		@Schema(description = "심사·정산 담당자가 연락할 번호. 구매자에게 공개되는 문의처는 "
				+ "설정 화면에서 따로 등록한다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "010-1234-5678")
		@NotBlank(message = "연락처는 필수입니다")
		@Pattern(regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$", message = "휴대폰 번호 형식이 아닙니다")
		String phone,

		@Schema(description = "심사·정산용 이메일. 구매자에게 나가지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "seller@moeum.store")
		@NotBlank(message = "이메일은 필수입니다")
		@Email(message = "이메일 형식이 아닙니다")
		@Size(max = 120, message = "120자를 넘을 수 없습니다")
		String email,

		@Schema(description = "배송비. 주문 묶음당 1회이고 2차금에서 청구된다", example = "3000")
		@Min(value = 0, message = "0 이상이어야 합니다")
		int shippingFee,

		@Schema(description = "이 금액 이상이면 배송비 면제. 비우면 미적용", example = "50000")
		@Min(value = 0, message = "0 이상이어야 합니다")
		Integer freeShippingOver
) {
}
