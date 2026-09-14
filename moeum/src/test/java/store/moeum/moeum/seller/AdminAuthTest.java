package store.moeum.moeum.seller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 심사 API 접근 제어 (D-055).
 *
 * <b>여기가 뚫리면 신청자의 대표자 실명 · 사업자등록번호 · 연락처가 통째로 나가고
 * 누구나 셀러를 승인·반려할 수 있다.</b> 컨트롤러에는 권한 검사가 없고 인터셉터 한 곳이
 * 전부라서, 그 한 곳이 살아 있는지를 여기서 본다.
 */
@TestPropertySource(properties = "moeum.auth.admin-kakao-ids=kakao-admin-1,kakao-admin-2")
class AdminAuthTest extends IntegrationTest {

	private static final SessionUser ADMIN = new SessionUser("kakao-admin-1", "운영자");
	private static final SessionUser OUTSIDER = new SessionUser("kakao-9999", "남의 계정");

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("로그인하지_않으면_401")
	void 로그인하지_않으면_401() throws Exception {
		mockMvc.perform(get("/admin/sellers"))
				.andExpect(status().isUnauthorized());
	}

	/** 로그인은 했지만 명단에 없는 사람. 401 과 나눠야 프론트가 화면을 다르게 그린다 */
	@Test
	@DisplayName("로그인해도_명단에_없으면_403")
	void 로그인해도_명단에_없으면_403() throws Exception {
		mockMvc.perform(get("/admin/sellers").sessionAttr(SessionKeys.LOGIN_USER, OUTSIDER))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
	}

	@Test
	@DisplayName("운영자는_신청자_목록을_본다")
	void 운영자는_신청자_목록을_본다() throws Exception {
		mockMvc.perform(get("/admin/sellers").sessionAttr(SessionKeys.LOGIN_USER, ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").exists());
	}

	/**
	 * 승인·반려도 같은 인터셉터를 탄다.
	 *
	 * 없는 셀러 id 를 쓴다 — 여기서 보려는 것은 승인 결과가 아니라 <b>권한 검사를 통과했는가</b>다.
	 * 403 이 아니라 404 가 나오면 인터셉터를 지나 컨트롤러까지 갔다는 뜻이다.
	 */
	@Test
	@DisplayName("승인_반려도_같은_검사를_탄다")
	void 승인_반려도_같은_검사를_탄다() throws Exception {
		mockMvc.perform(post("/admin/sellers/999999/approve").sessionAttr(SessionKeys.LOGIN_USER, OUTSIDER))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/admin/sellers/999999/reject").sessionAttr(SessionKeys.LOGIN_USER, OUTSIDER))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/admin/sellers/999999/approve").sessionAttr(SessionKeys.LOGIN_USER, ADMIN))
				.andExpect(status().isNotFound());
	}

	/** 프론트가 심사 화면 진입점을 보일지 판단하는 값 */
	@Test
	@DisplayName("me_가_운영자_여부를_준다")
	void me_가_운영자_여부를_준다() throws Exception {
		mockMvc.perform(get("/me").sessionAttr(SessionKeys.LOGIN_USER, ADMIN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.admin").value(true));

		mockMvc.perform(get("/me").sessionAttr(SessionKeys.LOGIN_USER, OUTSIDER))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.admin").value(false));
	}
}
