package store.moeum.moeum.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import store.moeum.moeum.outbox.domain.OutboxEventType;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림톡·배송조회 설정이 <b>모든 프로파일에 배선돼 있는가</b>.
 *
 * <b>이 테스트가 없어서 운영에서 알림톡이 조용히 꺼져 있었다.</b> prod 문서에
 * {@code moeum.notify} 블록이 통째로 빠져 있었는데, 그러면 {@code NOTIFY_PROVIDER} 를
 * solapi 로 놓아도 읽는 자리가 없어 {@code moeum.notify.provider} 가 정의되지 않고,
 * 발송기는 {@code LoggingNotificationSender}({@code matchIfMissing = true})가 잡는다 —
 * <b>예외도 경고도 없이 로그만 남는다.</b> 배포 문서대로 파라미터를 다 채워도 안 나간다.
 *
 * 스프링 컨텍스트를 띄우지 않고 yml 을 직접 읽는다. 프로파일마다 컨텍스트를 띄우면
 * DB 가 필요하고, 여기서 보려는 것은 빈이 아니라 <b>설정 파일에 자리가 있는가</b>다.
 */
class NotifyConfigWiringTest {

	private static final List<String> PROFILES = List.of("local", "prod");

	@Test
	@DisplayName("모든_프로파일이_알림_발송기_설정을_읽는_자리를_가진다")
	void 발송기_배선() throws IOException {
		for (String profile : PROFILES) {
			assertThat(valueOf(profile, "moeum.notify.provider"))
					.as("%s 프로파일에 moeum.notify.provider 가 없다 — "
							+ "NOTIFY_PROVIDER 를 solapi 로 놓아도 알림톡이 나가지 않는다", profile)
					.isEqualTo("${NOTIFY_PROVIDER:log}");
		}
	}

	@Test
	@DisplayName("모든_프로파일이_SOLAPI_자격증명을_읽는_자리를_가진다")
	void 자격증명_배선() throws IOException {
		List<String> keys = List.of("api-key", "api-secret", "pf-id", "from");

		for (String profile : PROFILES) {
			for (String key : keys) {
				assertThat(valueOf(profile, "moeum.notify.solapi." + key))
						.as("%s 프로파일에 solapi.%s 자리가 없다", profile, key)
						.isNotNull();
			}
		}
	}

	@Test
	@DisplayName("알림_이벤트_전부에_템플릿_자리가_있다")
	void 템플릿_배선() throws IOException {
		// 한 줄이 비어 있는 것과 아예 없는 것은 화면에서 똑같아 보인다.
		// 이벤트를 새로 만들고 자리를 안 만들면 여기서 걸린다
		for (String profile : PROFILES) {
			for (OutboxEventType event : OutboxEventType.values()) {
				assertThat(valueOf(profile, "moeum.notify.solapi.templates." + event.name()))
						.as("%s 프로파일에 %s 템플릿 자리가 없다 — 승인돼도 채울 곳이 없다",
								profile, event)
						.isEqualTo("${SOLAPI_TEMPLATE_" + event.name() + ":}");
			}
		}
	}

	@Test
	@DisplayName("모든_프로파일이_배송조회_설정을_읽는_자리를_가진다")
	void 배송조회_배선() throws IOException {
		for (String profile : PROFILES) {
			assertThat(valueOf(profile, "moeum.tracking.enabled"))
					.as("%s 프로파일에 moeum.tracking.enabled 가 없다", profile)
					.isEqualTo("${SMART_TRACKER_ENABLED:false}");
			assertThat(valueOf(profile, "moeum.tracking.api-key"))
					.as("%s 프로파일에 moeum.tracking.api-key 가 없다", profile)
					.isNotNull();
		}
	}

	// ---------------------------------------------------------------- 도우미

	/**
	 * 그 프로파일 문서에 실제로 적힌 값. 플레이스홀더를 풀지 않고 글자 그대로 본다 —
	 * 여기서 보려는 것은 값이 아니라 <b>읽는 자리가 있는가</b>다.
	 */
	private Object valueOf(String profile, String property) throws IOException {
		List<PropertySource<?>> documents = new YamlPropertySourceLoader()
				.load("application.yml", new ClassPathResource("application.yml"));

		// 문서 순서는 yml 에 적힌 순서다. 프로파일 문서를 찾아 그 안에서만 본다
		for (PropertySource<?> document : documents) {
			Object activated = document.getProperty("spring.config.activate.on-profile");
			if (profile.equals(activated)) {
				return document.getProperty(property);
			}
		}
		throw new AssertionError(profile + " 프로파일 문서를 찾지 못했다");
	}
}
