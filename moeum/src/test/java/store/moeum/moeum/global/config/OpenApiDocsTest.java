package store.moeum.moeum.global.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 문서가 실제로 생성되는지 본다.
 * 컨트롤러를 추가했는데 문서가 깨지는 걸 여기서 잡는다.
 */
class OpenApiDocsTest extends IntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("api-docs가_생성되고_엔드포인트가_들어_있다")
	void api_docs가_생성되고_엔드포인트가_들어_있다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("moeum-api"))
				.andExpect(jsonPath("$.paths['/seller/sale-forms'].post").exists())
				.andExpect(jsonPath("$.paths['/seller/sale-forms/{saleFormId}'].put").exists())
				.andExpect(jsonPath("$.paths['/seller/sale-forms/{saleFormId}/history'].get").exists())
				.andExpect(jsonPath("$.paths['/seller/onboarding'].post").exists())
				.andExpect(jsonPath("$.paths['/auth/kakao/login'].get").exists())
				.andExpect(jsonPath("$.paths['/me'].get").exists());
	}

	@Test
	@DisplayName("공통_에러_응답이_모든_오퍼레이션에_붙는다")
	void 공통_에러_응답이_모든_오퍼레이션에_붙는다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.ErrorResponse").exists())
				.andExpect(jsonPath(
						"$.paths['/seller/onboarding'].post.responses['400'].content['application/json'].schema.$ref")
						.value("#/components/schemas/ErrorResponse"))
				.andExpect(jsonPath("$.paths['/seller/onboarding'].post.responses['500']").exists());
	}

	@Test
	@DisplayName("로그인이_필요한_경로에만_401과_세션_인증이_붙는다")
	void 로그인이_필요한_경로에만_401과_세션_인증이_붙는다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.sessionCookie.in").value("cookie"))
				.andExpect(jsonPath("$.components.securitySchemes.sessionCookie.name").value("MOEUM_SESSION"))
				.andExpect(jsonPath("$.paths['/me'].get.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/me'].get.security[0].sessionCookie").exists())
				.andExpect(jsonPath("$.paths['/checkout-sessions'].post.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/checkout-sessions'].post.security[0].sessionCookie").exists())
				.andExpect(jsonPath("$.paths['/me/cart/items'].post.responses['401']").exists())
				// 로그인 시작은 인증이 필요 없다
				.andExpect(jsonPath("$.paths['/auth/kakao/login'].get.responses['401']").doesNotExist())
				.andExpect(jsonPath("$.paths['/auth/kakao/login'].get.security").doesNotExist());
	}
}
