package store.moeum.moeum.buyer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 배송지 등록·수정. PUT 전체 교체다 */
public record AddressRequest(

		@NotBlank(message = "받는 사람 이름은 필수입니다")
		@Size(max = 50, message = "50자를 넘을 수 없습니다")
		String recipientName,

		@NotBlank(message = "휴대폰 번호는 필수입니다")
		@Pattern(regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$", message = "휴대폰 번호 형식이 아닙니다")
		String phone,

		@Size(max = 10, message = "10자를 넘을 수 없습니다")
		String postalCode,

		@NotBlank(message = "주소는 필수입니다")
		@Size(max = 255, message = "255자를 넘을 수 없습니다")
		String address1,

		@Size(max = 255, message = "255자를 넘을 수 없습니다")
		String address2,

		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String memo
) {
}
