package store.moeum.moeum.global.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프론트가 브라우저에서 직접 호출하는 구조라 CORS 가 필요하다 (api-spec 0번).
 * 인증이 세션 쿠키이므로 allowCredentials 가 반드시 켜져 있어야 한다.
 */
@TestPropertySource(properties = "moeum.auth.allowed-origins=https://app.moeum.store,http://localhost:3000")
class CorsTest extends IntegrationTest {

	private static final String FRONT = "https://app.moeum.store";

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("허용된_출처의_프리플라이트에_쿠키_전송_허용이_붙는다")
	void 허용된_출처의_프리플라이트에_쿠키_전송_허용이_붙는다() throws Exception {
		mockMvc.perform(options("/seller/sale-forms")
						.header("Origin", FRONT)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONT))
				// 세션 쿠키를 실어 보내려면 이 헤더가 반드시 필요하다
				.andExpect(header().string("Access-Control-Allow-Credentials", "true"));
	}

	@Test
	@DisplayName("실제_요청_응답에도_Allow_Origin이_붙는다")
	void 실제_요청_응답에도_Allow_Origin이_붙는다() throws Exception {
		mockMvc.perform(get("/api/health").header("Origin", FRONT))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", FRONT));
	}

	@Test
	@DisplayName("허용되지_않은_출처의_프리플라이트는_거부된다")
	void 허용되지_않은_출처의_프리플라이트는_거부된다() throws Exception {
		mockMvc.perform(options("/seller/sale-forms")
						.header("Origin", "https://evil.com")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	@DisplayName("허용_목록은_Origin_검증_필터와_같은_값을_쓴다")
	void 허용_목록은_Origin_검증_필터와_같은_값을_쓴다() throws Exception {
		// CORS 가 열린 출처는 필터도 통과한다 (401 은 로그인 여부라 여기선 통과로 본다)
		mockMvc.perform(post("/auth/logout").header("Origin", FRONT))
				.andExpect(status().isNoContent());

		// 목록에 없는 출처는 필터가 막는다
		mockMvc.perform(post("/auth/logout").header("Origin", "https://evil.com"))
				.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("프리플라이트는_Origin_검증_필터에_막히지_않는다")
	void 프리플라이트는_Origin_검증_필터에_막히지_않는다() throws Exception {
		// OPTIONS 를 필터가 막으면 브라우저는 본 요청을 보내지도 못한다
		mockMvc.perform(options("/seller/onboarding")
						.header("Origin", FRONT)
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isOk());
	}
}
