package store.moeum.moeum.global.auth;

public final class SessionKeys {

	/** 로그인 주체 */
	public static final String LOGIN_USER = "moeum.loginUser";

	/**
	 * 카카오 access token. 서버 세션에만 둔다 — 쿠키·응답 본문 어디에도 싣지 않는다.
	 * 지금은 쓰는 곳이 없고, 알림톡·프로필 갱신이 필요해지면 여기서 꺼내 쓴다.
	 */
	public static final String KAKAO_ACCESS_TOKEN = "moeum.kakaoAccessToken";

	private SessionKeys() {
	}
}
