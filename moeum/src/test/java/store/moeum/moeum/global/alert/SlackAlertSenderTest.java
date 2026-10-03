package store.moeum.moeum.global.alert;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Slack Webhook 발송 (D-068). 실패해도 예외를 던지지 않는 것이 핵심이다 */
class SlackAlertSenderTest {

	private static final WireMockServer SLACK = new WireMockServer(wireMockConfig().dynamicPort());
	private static final String PATH = "/services/T000/B000/xxx";

	@BeforeAll
	static void start() {
		SLACK.start();
	}

	@AfterAll
	static void stop() {
		SLACK.stop();
	}

	@BeforeEach
	void reset() {
		SLACK.resetAll();
	}

	@Test
	@DisplayName("CRITICAL_은_채널_전체를_부른다")
	void 전송() {
		SLACK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("ok")));

		boolean delivered = sender(SLACK.baseUrl() + PATH).send(AlertLevel.CRITICAL, "마감 임박");

		assertThat(delivered).isTrue();
		SLACK.verify(postRequestedFor(urlPathEqualTo(PATH))
				.withRequestBody(equalToJson("""
						{"text":"<!channel> :rotating_light: 마감 임박"}""")));
	}

	@Test
	@DisplayName("Slack_이_실패하면_예외_대신_false_를_돌려준다")
	void 실패() {
		SLACK.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

		// 알림 실패로 대사 배치가 멈추면 안 된다
		assertThat(sender(SLACK.baseUrl() + PATH).send(AlertLevel.WARN, "x")).isFalse();
	}

	@Test
	@DisplayName("URL_이_비어_있으면_보내지_않고_로그만_남긴다")
	void 꺼짐() {
		assertThat(sender("").send(AlertLevel.WARN, "x")).isTrue();
		SLACK.verify(0, postRequestedFor(urlPathEqualTo(PATH)));
	}

	private static SlackAlertSender sender(String url) {
		return new SlackAlertSender(new SlackProperties(url, 500, 500));
	}
}
