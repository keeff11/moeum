package store.moeum.moeum.global.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** CSRF 2차 방어 (api-spec 10-⑪) */
class OriginValidationFilterTest {

	private static final List<String> ALLOWED = List.of("https://moeum.store");

	@Test
	@DisplayName("허용된_오리진의_POST는_통과한다")
	void 허용된_오리진의_POST는_통과한다() throws Exception {
		assertThat(status("POST", "https://moeum.store", ALLOWED)).isEqualTo(200);
	}

	@Test
	@DisplayName("다른_오리진의_POST는_403이다")
	void 다른_오리진의_POST는_403이다() throws Exception {
		assertThat(status("POST", "https://evil.com", ALLOWED)).isEqualTo(403);
	}

	@Test
	@DisplayName("다른_오리진이어도_GET은_통과한다")
	void 다른_오리진이어도_GET은_통과한다() throws Exception {
		assertThat(status("GET", "https://evil.com", ALLOWED)).isEqualTo(200);
	}

	@Test
	@DisplayName("Origin_헤더가_없으면_통과한다")
	void Origin_헤더가_없으면_통과한다() throws Exception {
		// 서버 간 호출(SSR)에는 Origin 이 붙지 않는다.
		// 막으려는 대상인 브라우저의 크로스 사이트 요청에는 반드시 붙는다
		assertThat(status("POST", null, ALLOWED)).isEqualTo(200);
	}

	@Test
	@DisplayName("허용_목록이_비어_있으면_검증하지_않는다")
	void 허용_목록이_비어_있으면_검증하지_않는다() throws Exception {
		assertThat(status("POST", "https://evil.com", List.of())).isEqualTo(200);
	}

	@Test
	@DisplayName("차단되면_다음_필터로_넘기지_않는다")
	void 차단되면_다음_필터로_넘기지_않는다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/seller/onboarding");
		request.addHeader("Origin", "https://evil.com");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		new OriginValidationFilter(ALLOWED).doFilter(request, response, chain);

		verify(chain, never()).doFilter(request, response);
		assertThat(response.getContentAsString()).contains("FORBIDDEN");
	}

	private int status(String method, String origin, List<String> allowed) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/seller/onboarding");
		if (origin != null) {
			request.addHeader("Origin", origin);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();

		new OriginValidationFilter(allowed).doFilter(request, response, new MockFilterChain());

		return response.getStatus();
	}
}
