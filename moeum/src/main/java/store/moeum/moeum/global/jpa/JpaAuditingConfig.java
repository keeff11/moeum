package store.moeum.moeum.global.jpa;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 감사 시각을 KST 로 고정한다.
 * JVM 기본 타임존에 기대면 운영 서버 설정에 따라 DB 의 NOW(6) 와 어긋난다.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "kstDateTimeProvider")
public class JpaAuditingConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Bean
	public DateTimeProvider kstDateTimeProvider() {
		return () -> Optional.of(LocalDateTime.now(KST));
	}
}
