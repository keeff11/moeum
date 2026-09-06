package store.moeum.moeum.global.auth;

public final class SessionKeys {

	/**
	 * 로그인 주체. 세션에 담기는 유일한 값이다.
	 *
	 * 카카오 access token 은 여기 두지 않는다 (D-020). 세션이 MySQL 에 저장되면서
	 * 세션 속성이 평문 blob 으로 디스크에 남게 됐는데, 지금 쓰는 곳이 없는 토큰을 그렇게 둘 이유가 없다.
	 * 알림톡·프로필 갱신에 필요해지면 그때 암호화해서 별도로 보관한다.
	 */
	public static final String LOGIN_USER = "moeum.loginUser";

	private SessionKeys() {
	}
}
