package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 환불 계좌 등록·수정. PUT 전체 교체다 (D-057).
 *
 * 세 항목 전부 필수다. 계좌번호만 있고 예금주가 비면 송금이 반려된다.
 */
public record RefundAccountRequest(

		@Schema(description = "은행명. 화면의 선택지를 그대로 보낸다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "국민은행")
		@NotBlank(message = "은행은 필수입니다")
		@Size(max = 30, message = "30자를 넘을 수 없습니다")
		String bank,

		@Schema(description = "계좌번호. 하이픈은 있어도 없어도 된다. 암호화해 저장하고 "
				+ "조회 응답에는 뒤 네 자리만 나간다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "1002-123-456789")
		@NotBlank(message = "계좌번호는 필수입니다")
		@Pattern(regexp = "^[0-9][0-9-]{7,29}$", message = "계좌번호 형식이 아닙니다")
		String accountNo,

		@Schema(description = "예금주. 받는 사람 이름과 다를 수 있어 따로 받는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "홍길동")
		@NotBlank(message = "예금주는 필수입니다")
		@Size(max = 50, message = "50자를 넘을 수 없습니다")
		String holderName
) {
}
