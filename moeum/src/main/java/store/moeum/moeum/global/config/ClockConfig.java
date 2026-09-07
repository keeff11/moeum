package store.moeum.moeum.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 시간을 의존성으로 주입한다.
 *
 * EOB 차단(23:30~00:30 KST)처럼 <b>시각이 곧 분기 조건</b>인 코드가 생겼는데,
 * {@code LocalDateTime.now()} 를 직접 부르면 테스트가 실행 시각에 좌우된다 —
 * 실제로 밤 11시 반에 돌리면 취소 테스트가 전부 깨진다.
 */
@Configuration
public class ClockConfig {

	@Bean
	@ConditionalOnMissingBean
	public Clock clock() {
		return Clock.system(KST);
	}
}
