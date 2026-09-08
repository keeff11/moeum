package store.moeum.moeum.global.health;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 가벼운 헬스체크. DB · 커넥션풀 상태까지 보려면 /actuator/health 를 쓴다.
 */
@Tag(name = "헬스체크", description = "앱 상태")
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final Environment environment;

	@Operation(summary = "헬스 체크",
			description = "배포·모니터링이 부른다. 프론트가 쓸 일은 없다.")
	@GetMapping
	public HealthResponse health() {
		return new HealthResponse(
				"UP",
				environment.getProperty("spring.application.name", "moeum"),
				profiles(),
				OffsetDateTime.now(KST)
		);
	}

	/** 활성 프로파일이 없으면 기본 프로파일(local)을 쓰고 있다는 뜻이다. */
	private List<String> profiles() {
		String[] active = environment.getActiveProfiles();
		return List.of(active.length > 0 ? active : environment.getDefaultProfiles());
	}

	@Schema(description = "서버 상태. 배포·모니터링용이다")
	public record HealthResponse(
			@Schema(description = "서버 상태", example = "UP") String status,
			@Schema(description = "애플리케이션 이름", example = "moeum") String application,
			@Schema(description = "적용된 스프링 프로파일") List<String> profiles,
			@Schema(description = "응답을 만든 시각") OffsetDateTime time) {
	}
}
