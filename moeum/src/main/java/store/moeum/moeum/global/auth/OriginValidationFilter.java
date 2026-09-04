package store.moeum.moeum.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * CSRF 방어의 두 번째 겹 (api-spec 10-⑪).
 *
 * 세션 쿠키가 SameSite=Lax 라 크로스 사이트 POST 에는 애초에 쿠키가 실리지 않는다.
 * 여기서는 그게 뚫렸을 때를 대비해 상태를 바꾸는 요청의 Origin 을 한 번 더 본다.
 * CSRF 토큰까지는 두지 않는다 — 세션 쿠키 + Lax + Origin 이면 충분하다.
 *
 * 판단 기준
 *  - GET · HEAD · OPTIONS 는 통과. 상태를 바꾸지 않는다
 *  - 허용 목록이 비어 있으면 통과. 로컬 개발에서 매번 오리진을 맞추게 하지 않는다
 *  - Origin 헤더가 없으면 통과. 서버 간 호출(SSR)과 일부 동일 출처 요청에는 붙지 않는다.
 *    브라우저의 크로스 사이트 요청에는 반드시 붙으므로, 막으려는 대상은 이 조건에 걸리지 않는다
 */
@Slf4j
public class OriginValidationFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	private final List<String> allowedOrigins;

	public OriginValidationFilter(List<String> allowedOrigins) {
		this.allowedOrigins = (allowedOrigins == null) ? List.of() : allowedOrigins;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
	                                FilterChain filterChain) throws ServletException, IOException {
		if (!isAllowed(request)) {
			log.warn("Origin 불일치로 거부: method={}, uri={}, origin={}",
					request.getMethod(), request.getRequestURI(), request.getHeader(HttpHeaders.ORIGIN));
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write(
					"{\"code\":\"FORBIDDEN\",\"message\":\"허용되지 않은 요청 출처입니다.\"}");
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isAllowed(HttpServletRequest request) {
		if (SAFE_METHODS.contains(request.getMethod()) || allowedOrigins.isEmpty()) {
			return true;
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		return origin == null || allowedOrigins.contains(origin);
	}
}
