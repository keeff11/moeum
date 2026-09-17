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
				.andExpect(jsonPath("$.paths['/seller/orders'].get").exists())
				.andExpect(jsonPath("$.paths['/seller/orders/{orderNo}'].get").exists())
				.andExpect(jsonPath("$.paths['/seller/orders/second-charge'].get").exists())
				.andExpect(jsonPath("$.paths['/seller/orders/second-charge'].post").exists())
				.andExpect(jsonPath("$.paths['/seller/home'].get").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers'].get").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers/{sellerId}/approve'].post").exists())
				.andExpect(jsonPath("$.paths['/me/orders/in-progress'].get").exists())
				.andExpect(jsonPath("$.paths['/me/orders'].get").exists())
				.andExpect(jsonPath("$.paths['/auth/kakao/login'].get").exists())
				.andExpect(jsonPath("$.paths['/me'].get").exists());
	}

	/** 알림 받을 번호 인증 (D-064). 세 개가 다 보이고 로그인이 필요하다고 적혀야 프론트가 붙인다 */
	@Test
	@DisplayName("번호_인증_API_세_개가_로그인_필요로_보인다")
	void 번호_인증() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/me/phone'].get.summary").value("알림 받을 번호 조회"))
				.andExpect(jsonPath("$.paths['/me/phone/verification'].post.summary").value("인증번호 받기"))
				.andExpect(jsonPath("$.paths['/me/phone/verification/confirm'].post.summary").value("인증번호 확인"))
				.andExpect(jsonPath("$.paths['/me/phone/verification'].post.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/me/phone/verification/confirm'].post.responses['401']").exists())
				.andExpect(jsonPath("$.components.schemas.PhoneVerificationRequest.properties.phone").exists())
				.andExpect(jsonPath("$.components.schemas.PhoneVerificationConfirmRequest.properties.code").exists())
				.andExpect(jsonPath("$.components.schemas.PhoneVerificationResponse.properties.expiresAt").exists())
				.andExpect(jsonPath("$.components.schemas.NotifyPhoneResponse.properties.phoneMasked").exists());
	}

	/**
	 * /admin/* 는 앱이 지킨다 (D-055).
	 *
	 * 인증 코드가 컨트롤러가 아닌 인터셉터에 있어서, 자동 생성에 맡기면
	 * <b>인증 없는 공개 API</b> 로 문서에 나간다. 프론트가 그걸 보고 미배포로 오인한 적이 있다.
	 */
	@Test
	@DisplayName("admin_경로에_인증과_403_이_명시된다")
	void admin_경로에_인증과_403_이_명시된다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/admin/sellers'].get.security[0].sessionCookie").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers'].get.responses.401").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers'].get.responses.403").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers/{sellerId}/approve'].post.responses.403").exists())
				.andExpect(jsonPath("$.paths['/admin/sellers/{sellerId}/reject'].post.responses.403").exists());
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
