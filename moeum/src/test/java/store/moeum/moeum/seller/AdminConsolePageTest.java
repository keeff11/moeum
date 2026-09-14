package store.moeum.moeum.seller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.support.IntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 심사 화면이 API 와 같은 출처에서 나오는지 본다 (D-052).
 *
 * <b>이 경로가 죽으면 어드민이 통째로 막힌다.</b> 화면을 다른 출처에 두면 기본인증이
 * 프리플라이트에 걸려 동작하지 않아서, 같은 출처에서 서빙되는 것 자체가 설계의 전제다.
 * 정적 파일을 옮기거나 뷰 컨트롤러를 지우면 여기서 걸린다.
 */
class AdminConsolePageTest extends IntegrationTest {

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("심사_화면이_API_와_같은_출처에서_나온다")
	void 심사_화면이_API_와_같은_출처에서_나온다() throws Exception {
		mockMvc.perform(get("/admin/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"));
	}

	/**
	 * 정적 리소스 핸들러는 디렉터리를 색인으로 풀어주지 않아서 넘겨줘야 한다.
	 *
	 * MockMvc 는 포워드를 실제로 실행하지 않으므로 대상 주소만 본다 —
	 * 그 주소가 진짜 200 이라는 것은 위 테스트가 따로 확인한다.
	 */
	@Test
	@DisplayName("슬래시로_끝나는_admin_은_색인으로_넘긴다")
	void 슬래시로_끝나는_admin_은_색인으로_넘긴다() throws Exception {
		mockMvc.perform(get("/admin/"))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl("/admin/index.html"));
	}

	@Test
	@DisplayName("슬래시_없는_admin_은_슬래시로_넘긴다")
	void 슬래시_없는_admin_은_슬래시로_넘긴다() throws Exception {
		mockMvc.perform(get("/admin"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/admin/"));
	}
}
