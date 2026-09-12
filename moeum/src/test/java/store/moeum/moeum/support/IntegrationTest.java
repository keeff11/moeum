package store.moeum.moeum.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 MySQL 8.0 위에서 도는 통합 테스트의 부모.
 *
 * H2 로 대체하지 않는다. 3단계 이후 조건부 UPDATE · FOR UPDATE SKIP LOCKED · 락 타임아웃처럼
 * 엔진 동작에 의존하는 코드를 검증해야 하는데, H2 는 그 의미가 다르다.
 *
 * 컨테이너는 static 싱글턴으로 한 번만 띄우고 모든 테스트 클래스가 공유한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(OrderFixture.class)
public abstract class IntegrationTest {

	protected static final MySQLContainer<?> MYSQL =
			new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
					.withDatabaseName("moeum")
					.withUsername("moeum")
					.withPassword("moeum")
					.withUrlParam("characterEncoding", "UTF-8")
					.withUrlParam("connectionTimeZone", "Asia/Seoul")
					.withCommand(
							"--character-set-server=utf8mb4",
							"--collation-server=utf8mb4_0900_ai_ci",
							"--default-time-zone=+09:00",
							"--innodb-lock-wait-timeout=5",
							// 테스트 클래스마다 @DynamicPropertySource 가 다르면 스프링 컨텍스트가
							// 따로 뜨고, 그때마다 Hikari 풀(기본 10)이 하나씩 더 생긴다. 컨텍스트가
							// 열몇 개가 되면 MySQL 기본 상한 151 을 넘겨 "Too many connections" 로
							// 뒤쪽 클래스가 통째로 실패한다 — 테스트 코드는 멀쩡한데 컨텍스트가 안 뜬다
							"--max-connections=500"
					)
					.withReuse(true);

	static {
		// presigned URL 서명에만 쓰는 더미 자격증명. 네트워크로 나가지 않는다 —
		// 서명은 전부 로컬 계산이라 실제 AWS 없이도 발급 로직을 검증할 수 있다.
		System.setProperty("aws.accessKeyId", "test-access-key");
		System.setProperty("aws.secretAccessKey", "test-secret-key");
		System.setProperty("aws.region", "ap-northeast-2");

		MYSQL.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
	}
}
