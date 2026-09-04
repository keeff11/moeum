package store.moeum.moeum.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import store.moeum.moeum.global.auth.OriginValidationFilter;
import store.moeum.moeum.global.auth.SessionUserArgumentResolver;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	private static final long PREFLIGHT_CACHE_SECONDS = 3600;

	/**
	 * 믿는 프론트 출처 목록. CORS 허용과 Origin 검증이 같은 목록을 쓴다 —
	 * 둘이 갈라지면 "CORS 는 통과했는데 필터가 막는" 상황이 생긴다.
	 *
	 * 비어 있으면 CORS 를 열지 않고 Origin 검증도 하지 않는다.
	 * 로컬에서 curl · Swagger 로 찌를 때는 동일 출처라 둘 다 필요 없기 때문이다.
	 * 운영 프로파일에서는 ${ALLOWED_ORIGINS} 가 필수다.
	 */
	@Value("${moeum.auth.allowed-origins:}")
	private List<String> configuredOrigins;

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		// 빈으로 두지 않고 여기서 만든다. WebConfig 가 다른 빈에 의존하면
		// @WebMvcTest 같은 슬라이스에서 컨텍스트가 뜨지 않는다
		resolvers.add(new SessionUserArgumentResolver());
	}

	/**
	 * 프론트가 브라우저에서 직접 부른다 (api-spec 0번). BFF 계층이 없어서 CORS 가 필요하다.
	 *
	 * 인증이 세션 쿠키라 allowCredentials 가 켜져 있어야 한다.
	 * 그래서 allowedOrigins 에 와일드카드를 쓸 수 없다 — 출처를 하나씩 적어야 한다.
	 */
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		List<String> origins = allowedOrigins();
		if (origins.isEmpty()) {
			return;
		}
		registry.addMapping("/**")
				.allowedOrigins(origins.toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
				.allowedHeaders("*")
				.allowCredentials(true)
				.maxAge(PREFLIGHT_CACHE_SECONDS);
	}

	@Bean
	public FilterRegistrationBean<OriginValidationFilter> originValidationFilter() {
		FilterRegistrationBean<OriginValidationFilter> registration =
				new FilterRegistrationBean<>(new OriginValidationFilter(allowedOrigins()));
		registration.addUrlPatterns("/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
		return registration;
	}

	/** 빈 값이나 공백만 있는 항목을 걸러낸다. 프로퍼티가 비면 빈 리스트다 */
	private List<String> allowedOrigins() {
		if (configuredOrigins == null) {
			return List.of();
		}
		return configuredOrigins.stream()
				.filter(origin -> origin != null && !origin.isBlank())
				.map(String::trim)
				.toList();
	}
}
