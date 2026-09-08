package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 배송지 등록·수정. PUT 전체 교체다 */
public record AddressRequest(

		@Schema(description = "받는 사람 이름. 카카오 닉네임과 별개로 직접 입력받는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "홍길동")
		@NotBlank(message = "받는 사람 이름은 필수입니다")
		@Size(max = 50, message = "50자를 넘을 수 없습니다")
		String recipientName,

		@Schema(description = "받는 사람 휴대폰 번호. 하이픈은 있어도 없어도 된다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "010-1234-5678")
		@NotBlank(message = "휴대폰 번호는 필수입니다")
		@Pattern(regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$", message = "휴대폰 번호 형식이 아닙니다")
		String phone,

		@Schema(description = "우편번호. 주소 검색은 프론트가 외부 API 로 처리한다", example = "06234")
		@Size(max = 10, message = "10자를 넘을 수 없습니다")
		String postalCode,

		@Schema(description = "기본 주소", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "서울 강남구 테헤란로 1")
		@NotBlank(message = "주소는 필수입니다")
		@Size(max = 255, message = "255자를 넘을 수 없습니다")
		String address1,

		@Schema(description = "상세 주소", example = "101동 1001호")
		@Size(max = 255, message = "255자를 넘을 수 없습니다")
		String address2,

		@Schema(description = "배송 메모. 자유 입력이다", example = "부재 시 경비실에 맡겨주세요")
		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String memo
) {
}
