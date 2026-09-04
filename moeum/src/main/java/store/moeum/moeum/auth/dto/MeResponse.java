package store.moeum.moeum.auth.dto;

import store.moeum.moeum.global.auth.SessionUser;

/** 로그인 사용자 프로필. 카카오 토큰은 절대 담지 않는다 */
public record MeResponse(String kakaoId, String nickname) {

	public static MeResponse from(SessionUser user) {
		return new MeResponse(user.kakaoId(), user.nickname());
	}
}
