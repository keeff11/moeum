package store.moeum.moeum.global.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Slack Incoming Webhook 설정 (D-068).
 *
 * <b>URL 이 곧 비밀값이다.</b> 아는 사람은 누구나 채널에 글을 쓸 수 있다.
 * 저장소에 두지 않고 Parameter Store 에서 환경변수로 주입한다 (D-017).
 *
 * @param webhookUrl 비어 있으면 Slack 으로 보내지 않고 로그만 남긴다 — 로컬 · 테스트 기본값이다
 */
@ConfigurationProperties(prefix = "moeum.alert.slack")
public record SlackProperties(
		String webhookUrl,
		int connectTimeout,
		int readTimeout
) {

	@ConstructorBinding
	public SlackProperties {
		connectTimeout = (connectTimeout <= 0) ? 2000 : connectTimeout;
		readTimeout = (readTimeout <= 0) ? 3000 : readTimeout;
	}

	public boolean enabled() {
		return webhookUrl != null && !webhookUrl.isBlank();
	}
}
