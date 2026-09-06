package store.moeum.moeum.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.auth.infra.KakaoOAuthClient;
import store.moeum.moeum.auth.infra.KakaoProfile;
import store.moeum.moeum.auth.infra.KakaoTokens;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.support.IntegrationTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카카오 로그인 왕복.
 *
 * 카카오 서버 호출은 스텁으로 바꾸고, state 대조 · returnTo 검증 · 토큰이 브라우저로 새지 않는지를 본다.
 */
class AuthControllerTest extends IntegrationTest {

	private static final String ACCESS_TOKEN = "kakao-access-token-should-not-leak";

	@Autowired
	private WebApplicationContext context;

	/** 카카오 서버는 부르지 않는다. 여기서 볼 것은 state 대조 · returnTo 검증 · 토큰 유출 여부다 */
	@MockitoBean
	private KakaoOAuthClient kakaoClient;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

		given(kakaoClient.authorizeUrl(any()))
				.willAnswer(invocation -> "https://kauth.kakao.com/oauth/authorize?state=" + invocation.getArgument(0));
		given(kakaoClient.exchangeToken(any()))
				.willReturn(new KakaoTokens(ACCESS_TOKEN, "kakao-refresh-token", 21600));
		given(kakaoClient.fetchProfile(any()))
				.willReturn(new KakaoProfile(1234567890L,
						new KakaoProfile.KakaoAccount(new KakaoProfile.KakaoAccount.Profile("모으미"))));
	}

	@Test
	@DisplayName("로그인_시작은_state를_httpOnly_쿠키로_발급하고_카카오로_302한다")
	void 로그인_시작은_state를_httpOnly_쿠키로_발급하고_카카오로_302한다() throws Exception {
		MvcResult result = mockMvc.perform(get("/auth/kakao/login").param("returnTo", "/checkout"))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION,
						org.hamcrest.Matchers.startsWith("https://kauth.kakao.com/oauth/authorize")))
				.andReturn();

		List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		String stateCookie = cookieHeader(setCookies, OAuthCookies.STATE);
		String returnToCookie = cookieHeader(setCookies, OAuthCookies.RETURN_TO);

		assertThat(stateCookie).contains("HttpOnly").contains("SameSite=Lax");
		assertThat(returnToCookie).contains("HttpOnly").contains("%2Fcheckout");
		assertThat(valueOf(stateCookie)).isNotBlank();
	}

	@Test
	@DisplayName("콜백은_state가_맞으면_세션을_발급하고_returnTo로_302한다")
	void 콜백은_state가_맞으면_세션을_발급하고_returnTo로_302한다() throws Exception {
		MockHttpSession session = new MockHttpSession();

		MvcResult result = mockMvc.perform(get("/auth/kakao/callback")
						.param("code", "auth-code")
						.param("state", "the-state")
						.cookie(new Cookie(OAuthCookies.STATE, "the-state"))
						.cookie(new Cookie(OAuthCookies.RETURN_TO, "/checkout"))
						.session(session))
				.andExpect(status().isFound())
				.andExpect(header().string(HttpHeaders.LOCATION, "/checkout"))
				.andReturn();

		// 세션 고정 방지로 세션을 새로 만들므로 요청에 실린 세션이 아니라 응답의 세션을 본다
		Object user = result.getRequest().getSession(false).getAttribute(SessionKeys.LOGIN_USER);
		assertThat(user).isInstanceOf(SessionUser.class);
		assertThat(((SessionUser) user).kakaoId()).isEqualTo("1234567890");
		assertThat(((SessionUser) user).nickname()).isEqualTo("모으미");
	}

	@Test
	@DisplayName("카카오_토큰은_응답에도_세션에도_남지_않는다")
	void 카카오_토큰은_응답에도_세션에도_남지_않는다() throws Exception {
		MvcResult result = mockMvc.perform(get("/auth/kakao/callback")
						.param("code", "auth-code")
						.param("state", "the-state")
						.cookie(new Cookie(OAuthCookies.STATE, "the-state")))
				.andExpect(status().isFound())
				.andReturn();

		String body = result.getResponse().getContentAsString();
		String headers = result.getResponse().getHeaderNames().stream()
				.map(name -> name + "=" + String.join(",", result.getResponse().getHeaders(name)))
				.reduce("", (a, b) -> a + "\n" + b);

		assertThat(body).doesNotContain(ACCESS_TOKEN);
		assertThat(headers).doesNotContain(ACCESS_TOKEN).doesNotContain("kakao-refresh-token");

		// 세션에도 넣지 않는다 (D-020). 세션이 MySQL 에 저장되므로 넣으면 평문 blob 으로 디스크에 남는다.
		// 담기는 값은 로그인 주체 하나뿐이다.
		HttpSession session = result.getRequest().getSession(false);
		assertThat(Collections.list(session.getAttributeNames()))
				.containsExactly(SessionKeys.LOGIN_USER);
	}

	@Test
	@DisplayName("state가_다르면_401이_아니라_400으로_거부한다")
	void state가_다르면_400으로_거부한다() throws Exception {
		mockMvc.perform(get("/auth/kakao/callback")
						.param("code", "auth-code")
						.param("state", "attacker-state")
						.cookie(new Cookie(OAuthCookies.STATE, "the-state")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OAUTH_STATE_MISMATCH"));
	}

	@Test
	@DisplayName("state_쿠키가_아예_없으면_거부한다")
	void state_쿠키가_아예_없으면_거부한다() throws Exception {
		mockMvc.perform(get("/auth/kakao/callback").param("code", "auth-code").param("state", "the-state"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OAUTH_STATE_MISMATCH"));
	}

	@Test
	@DisplayName("동의를_취소하면_OAUTH_DENIED다")
	void 동의를_취소하면_OAUTH_DENIED다() throws Exception {
		mockMvc.perform(get("/auth/kakao/callback")
						.param("error", "access_denied")
						.cookie(new Cookie(OAuthCookies.STATE, "the-state")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("OAUTH_DENIED"));
	}

	@Test
	@DisplayName("returnTo가_외부_주소면_루트로_보낸다")
	void returnTo가_외부_주소면_루트로_보낸다() throws Exception {
		for (String evil : List.of("//evil.com", "https://evil.com", "/\\evil.com", "evil.com")) {
			mockMvc.perform(get("/auth/kakao/callback")
							.param("code", "auth-code")
							.param("state", "the-state")
							.cookie(new Cookie(OAuthCookies.STATE, "the-state"))
							.cookie(new Cookie(OAuthCookies.RETURN_TO, evil)))
					.andExpect(status().isFound())
					.andExpect(header().string(HttpHeaders.LOCATION, "/"));
		}
	}

	@Test
	@DisplayName("로그인하지_않으면_me는_401이다")
	void 로그인하지_않으면_me는_401이다() throws Exception {
		mockMvc.perform(get("/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("로그아웃하면_세션이_폐기된다")
	void 로그아웃하면_세션이_폐기된다() throws Exception {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser("1234567890", "모으미"));

		mockMvc.perform(get("/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.kakaoId").value("1234567890"));

		mockMvc.perform(post("/auth/logout").session(session))
				.andExpect(status().isNoContent());

		assertThat(session.isInvalid()).isTrue();
	}

	private static String cookieHeader(List<String> setCookies, String name) {
		return setCookies.stream()
				.filter(header -> header.startsWith(name + "="))
				.findFirst()
				.orElseThrow(() -> new AssertionError(name + " 쿠키가 없다: " + setCookies));
	}

	private static String valueOf(String cookieHeader) {
		String firstPair = Arrays.stream(cookieHeader.split(";")).findFirst().orElse("");
		return firstPair.substring(firstPair.indexOf('=') + 1);
	}
}
