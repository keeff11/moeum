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
		String nickname) {

	public static MeResponse from(SessionUser user) {
		return new MeResponse(user.kakaoId(), user.nickname());
	}
}
