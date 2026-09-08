package store.moeum.moeum.seller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 셀러 페이지(B0) 헤더에 나가는 공개 프로필.
 *
 * <b>심사용 정보는 여기서 못 바꾼다.</b> 대표자 실명 · 사업자번호 · 정산계좌는
 * 승인의 근거라서 셀러가 마음대로 갈아 끼우면 심사가 무의미해진다.
 * 그건 바꿔야 할 일이 생기면 운영자를 거친다.
 *
 * @param profileImageKey presigned URL 로 올린 뒤 받은 객체 키. 비우면 지운다
 */
public record SellerProfileRequest(

		@NotBlank(message = "상점 이름을 입력해 주세요.")
		@Size(max = 60, message = "상점 이름은 60자를 넘을 수 없습니다.")
		String storeName,

		@Size(max = 100, message = "소개는 100자를 넘을 수 없습니다.")
		String bio,

		// 스킴을 강제한다. 뒤에 그대로 링크가 걸리므로 javascript: 같은 값이 들어오면 안 된다
		@Pattern(regexp = "^$|^https?://.{1,190}$", message = "소셜 주소는 http(s) 로 시작해야 합니다.")
		@Size(max = 200, message = "소셜 주소는 200자를 넘을 수 없습니다.")
		String socialUrl,

		@Size(max = 500)
		String profileImageKey
) {
}
