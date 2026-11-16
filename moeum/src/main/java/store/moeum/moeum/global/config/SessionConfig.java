package store.moeum.moeum.global.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.RedisSessionRepository;
import store.moeum.moeum.global.auth.TouchTolerantSessionRepository;

/**
 * Spring Boot 가 만든 Redis 세션 저장소를 {@link TouchTolerantSessionRepository} 로 감싼다 (D-072).
 *
 * 저장소 빈을 직접 정의하지 않는 이유 — {@code SessionRepository} 빈이 따로 있으면 Boot 의 Redis 세션
 * 자동 구성이 통째로 빠지고, namespace · 만료 · 쿠키 연결을 손으로 다시 맞춰야 한다 (D-066 함정과 같은 종류).
 * 여기서는 다 만들어진 빈을 감싸기만 한다. {@code springSessionRepositoryFilter} 는 감싼 빈을 주입받는다.
 */
@Configuration
public class SessionConfig {

	/** BeanPostProcessor 는 다른 빈보다 먼저 떠야 해서 static 으로 둔다 */
	@Bean
	static BeanPostProcessor touchTolerantSessionRepositoryWrapper() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof RedisSessionRepository repository) {
					return TouchTolerantSessionRepository.wrap(repository);
				}
				return bean;
			}
		};
	}
}
