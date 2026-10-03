package store.moeum.moeum.global.alert;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Slack Incoming Webhook 으로 운영 알림을 보낸다 (D-068).
 *
 * <b>Slack 이 실패해도 로그에는 반드시 남는다.</b> 보내기 전에 먼저 로그를 찍는다 —
 * Webhook 이 끊긴 날 알림이 어디에도 안 남으면 사고를 사후에도 못 찾는다.
 *
 * 4xx · 5xx 를 나누지 않는다. 결제 호출과 달리 "보냈을 수도 있다" 가 문제 되지 않는다 —
 * 최악이 같은 알림 두 번이다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SlackProperties.class)
public class SlackAlertSender implements AlertSender {

	private final SlackProperties properties;
	private final RestClient restClient;

	public SlackAlertSender(SlackProperties properties) {
		this.properties = properties;

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());

		this.restClient = RestClient.builder().requestFactory(factory).build();
	}

	@Override
	public boolean send(AlertLevel level, String text) {
		if (level == AlertLevel.CRITICAL) {
			log.error("[운영 알림] {}", text);
		} else {
			log.warn("[운영 알림] {}", text);
		}

		if (!properties.enabled()) {
			return true;
		}

		try {
			restClient.post()
					.uri(properties.webhookUrl())
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("text", level.prefix() + text))
					.retrieve()
					.toBodilessEntity();
			return true;
		} catch (RuntimeException e) {
			// 메시지는 찍지 않는다. I/O 오류 메시지에 Webhook URL(비밀값)이 그대로 들어 있다
			log.error("Slack 알림 전송 실패: {}", e.getClass().getSimpleName());
			return false;
		}
	}
}
