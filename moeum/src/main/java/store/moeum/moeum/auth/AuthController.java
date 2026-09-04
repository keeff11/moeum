package store.moeum.moeum.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.auth.dto.MeResponse;
import store.moeum.moeum.auth.infra.KakaoOAuthClient;
import store.moeum.moeum.auth.infra.KakaoProfile;
import store.moeum.moeum.auth.infra.KakaoTokens;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.net.URI;

/**
 * 카카오 인가 코드 흐름. 로그인 시작 · 콜백 · 토큰 교환 · 세션 발급까지 서버가 전부 갖는다.
 *
 * 브라우저에 나가는 것은 세션 ID 쿠키뿐이다. 카카오 access/refresh 토큰은 서버 세션에만 둔다.
 */
@Slf4j
@Tag(name = "인증", description = "카카오 로그인 · 세션")
@RestController
@RequiredArgsConstructor
public class AuthController {

	private final KakaoOAuthClient kakaoClient;
	private final OAuthCookies cookies;

	/** 1단계 — state 발급 + returnTo 저장 후 카카오 동의 화면으로 302 */
	@GetMapping("/auth/kakao/login")
	public ResponseEntity<Void> login(@RequestParam(required = false) String returnTo,
	                                  HttpServletResponse response) {
		String state = cookies.newState();
		cookies.issue(response, state, returnTo);

		return ResponseEntity.status(302)
				.location(URI.create(kakaoClient.authorizeUrl(state)))
				.build();
	}

	/**
	 * 3단계 — state 대조 → 토큰 교환 → 프로필 조회 → 세션 발급 → returnTo 로 302.
	 *
	 * 외부 호출(토큰 교환 · 프로필 조회)은 트랜잭션 밖이다. 여기서 DB 를 건드리지 않는다.
	 */
	@GetMapping("/auth/kakao/callback")
	public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
	                                     @RequestParam(required = false) String state,
	                                     @RequestParam(required = false) String error,
	                                     HttpServletRequest request,
	                                     HttpServletResponse response) {
		String cookieState = cookies.read(request, OAuthCookies.STATE).orElse(null);
		String returnTo = cookies.readReturnTo(request);
		cookies.clear(response);

		if (error != null) {
			// 사용자가 동의를 취소한 경우
			throw new BusinessException(ErrorCode.OAUTH_DENIED);
		}
		if (cookieState == null || state == null || !constantTimeEquals(cookieState, state)) {
			throw new BusinessException(ErrorCode.OAUTH_STATE_MISMATCH);
		}
		if (code == null || code.isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_STATE_MISMATCH);
		}

		KakaoTokens tokens = kakaoClient.exchangeToken(code);
		KakaoProfile profile = kakaoClient.fetchProfile(tokens.accessToken());

		// 세션 고정 공격 방지 — 로그인 시점에 세션을 새로 만든다
		HttpSession existing = request.getSession(false);
		if (existing != null) {
			existing.invalidate();
		}
		HttpSession session = request.getSession(true);
		session.setAttribute(SessionKeys.LOGIN_USER,
				new SessionUser(profile.kakaoId(), profile.nickname()));
		session.setAttribute(SessionKeys.KAKAO_ACCESS_TOKEN, tokens.accessToken());

		log.info("카카오 로그인 성공: kakaoId={}", mask(profile.kakaoId()));

		return ResponseEntity.status(302)
				.header(HttpHeaders.LOCATION, returnTo)
				.build();
	}

	@PostMapping("/auth/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	public MeResponse me(@LoginUser SessionUser user) {
		return MeResponse.from(user);
	}

	/** state 비교는 길이가 달라도 조기 반환하지 않는다 */
	private static boolean constantTimeEquals(String a, String b) {
		return java.security.MessageDigest.isEqual(
				a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String mask(String kakaoId) {
		if (kakaoId == null || kakaoId.length() <= 4) {
			return "****";
		}
		return kakaoId.substring(0, 2) + "****" + kakaoId.substring(kakaoId.length() - 2);
	}
}
