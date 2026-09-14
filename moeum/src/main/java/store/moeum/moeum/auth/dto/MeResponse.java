package store.moeum.moeum.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.global.auth.SessionUser;

/** 로그인 사용자 프로필. 카카오 토큰은 절대 담지 않는다 */
@Schema(description = "로그인한 사용자 정보")
public record MeResponse(

		@Schema(description = "카카오 계정 식별자. 구매자·셀러 어느 쪽이든 이 값이 본인 확인 기준이다")
		String kakaoId,

		@Schema(description = "카카오 닉네임. 받는 사람 이름과는 별개다 — 배송지는 따로 입력받는다",
				example = "길동")
		String nickname,

		@Schema(description = "운영자인가. 심사 화면 진입점을 보일지 판단하는 데 쓴다. 화면을 가리는 것은 편의일 뿐이고 실제 차단은 서버가 한다 (D-055)",
				example = "false")
		boolean admin) {

	/**
	 * <b>role 문자열이 아니라 boolean 이다.</b> 이 서비스에는 역할 체계가 없다 —
	 * 셀러와 구매자는 별개 테이블이고 한 사람이 양쪽을 겸할 수 있다 (D-019).
	 * role 을 하나 주면 그 것들이 배타적인 것처럼 보이게 된다.
	 */
	public static MeResponse from(SessionUser user, boolean admin) {
		return new MeResponse(user.kakaoId(), user.nickname(), admin);
	}
}
