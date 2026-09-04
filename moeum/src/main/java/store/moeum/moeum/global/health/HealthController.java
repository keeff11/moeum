package store.moeum.moeum.global.health;

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

	public record HealthResponse(String status, String application, List<String> profiles, OffsetDateTime time) {
	}
}
