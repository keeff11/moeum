package store.moeum.moeum.outbox;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.outbox.infra.SolapiClient;
import store.moeum.moeum.outbox.infra.SolapiFailedException;
import store.moeum.moeum.outbox.infra.SolapiProperties;
import store.moeum.moeum.outbox.infra.SolapiSendRequest;
import store.moeum.moeum.outbox.infra.SolapiUncertainException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SOLAPI 호출의 실패 처리.
 *
 * <b>여기서 검증하는 것은 성공 경로가 아니라 갈림길이다.</b> point3 와 같은 이유로
 * 4xx 와 5xx 를 나누고(CLAUDE.md 규칙 2), 그 위에 알림톡 고유의 함정이 하나 더 있다 —
 * <b>HTTP 200 인데 한 건도 안 나간 응답.</b> 이걸 성공으로 치면 outbox 가 SENT 로
 * 넘어가 2차금 청구가 영영 사라진다.
 *
 * 실제 SOLAPI 로는 타임아웃과 5xx 를 요청해서 받을 수 없다. WireMock 으로 만든다.
 */
class SolapiClientTest {

	private static final String API_KEY = "TEST_API_KEY";
	private static final String API_SECRET = "test-api-secret";
	private static final String PF_ID = "KA01PF260101000000000000000000";
	private static final String TEMPLATE_ID = "KA01TP260101000000000000000000";
	private static final String SEND_PATH = "/messages/v4/send-many/detail";

	private WireMockServer server;
	private SolapiClient client;

	@BeforeEach
	void setUp() {
		server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		client = clientWith(10000);
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	// ---------------------------------------------------------------- 성공 경로

	@Test
	@DisplayName("접수되면_예외가_없다")
	void 발송_성공() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(200, """
						{"failedMessageList":[],
						 "messageList":[{"messageId":"M4V2026","statusCode":"2000"}]}
						""")));

		assertThatCode(() -> client.send(message())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("요청에_알림톡_파라미터가_그대로_실린다")
	void 요청_본문() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(200, "{\"failedMessageList\":[]}")));

		client.send(message());

		LoggedRequest request = server.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH))).get(0);
		String body = request.getBodyAsString();

		assertThat(body).contains("\"pfId\":\"" + PF_ID + "\"");
		assertThat(body).contains("\"templateId\":\"" + TEMPLATE_ID + "\"");
		// 변수 키는 #{} 를 붙인 그대로 나가야 템플릿과 맞는다
		assertThat(body).contains("#{userName}");
		// 대체 발송을 막지 않는다 — 카카오톡을 안 쓰는 구매자에게도 청구는 닿아야 한다
		assertThat(body).contains("\"disableSms\":false");
		// 같은 내용이 두 번 접수되는 것을 SOLAPI 쪽에서도 막는다
		assertThat(body).contains("\"allowDuplicates\":false");
	}

	@Test
	@DisplayName("서명은_date와_salt를_이어_붙여_apiSecret으로_만든다")
	void 인증_헤더() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(200, "{\"failedMessageList\":[]}")));

		client.send(message());

		String header = server.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)))
				.get(0).getHeader("Authorization");

		assertThat(header).startsWith("HMAC-SHA256 Apikey=" + API_KEY);

		// 서명이 실제로 맞는지 같은 방식으로 다시 계산해 본다.
		// 틀리면 SOLAPI 가 401 을 주는데, 그건 배포한 뒤에야 알게 된다
		Matcher matcher = Pattern
				.compile("Date=(?<date>[^,]+), salt=(?<salt>[^,]+), signature=(?<signature>[0-9a-f]+)")
				.matcher(header);

		assertThat(matcher.find()).isTrue();
		assertThat(matcher.group("salt")).hasSize(32).doesNotContain("-");
		assertThat(matcher.group("signature"))
				.isEqualTo(hmac(matcher.group("date") + matcher.group("salt")));
	}

	// ---------------------------------------------------------------- 갈림길

	@Test
	@DisplayName("200_인데_건별_실패가_담겨_오면_성공이_아니다")
	void 건별_실패() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(200, """
						{"failedMessageList":[
						   {"to":"01012341234","statusCode":"3065",
						    "statusMessage":"등록되지 않은 템플릿입니다."}],
						 "messageList":[]}
						""")));

		// 여기서 성공으로 치면 outbox 가 SENT 로 넘어가 다시는 안 보낸다 —
		// 한 건도 안 나갔는데 보냈다고 기록되는 것이다
		assertThatThrownBy(() -> client.send(message()))
				.isInstanceOf(SolapiUncertainException.class)
				.hasMessageContaining("3065");
	}

	@Test
	@DisplayName("4xx_는_확정_실패다")
	void 사백번대() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(401, "{\"errorCode\":\"InvalidApiKey\"}")));

		assertThatThrownBy(() -> client.send(message()))
				.isInstanceOf(SolapiFailedException.class);
	}

	@Test
	@DisplayName("5xx_는_결과_불명이다")
	void 오백번대() {
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(500, "{}")));

		// 실제로 나갔을 수 있다. 재시도해서 두 번 가는 편이 안 가는 것보다 낫다
		assertThatThrownBy(() -> client.send(message()))
				.isInstanceOf(SolapiUncertainException.class);
	}

	@Test
	@DisplayName("타임아웃도_결과_불명이다")
	void 타임아웃() {
		SolapiClient impatient = clientWith(300);
		server.stubFor(WireMock.post(urlPathEqualTo(SEND_PATH))
				.willReturn(json(200, "{\"failedMessageList\":[]}").withFixedDelay(2000)));

		assertThatThrownBy(() -> impatient.send(message()))
				.isInstanceOf(SolapiUncertainException.class);
	}

	@Test
	@DisplayName("설정이_비어_있으면_호출하기_전에_막는다")
	void 설정_누락() {
		SolapiClient unconfigured = new SolapiClient(new SolapiProperties(
				server.baseUrl(), "", "", "", "", "https://www.moeum.store", null,
				Map.of(OutboxEventType.ORDER_PAID, TEMPLATE_ID), 2000, 10000));

		assertThatThrownBy(() -> unconfigured.send(message()))
				.isInstanceOf(SolapiFailedException.class);

		// 키가 없는 채로 요청을 날리면 SOLAPI 로그에 실패만 쌓인다
		assertThat(server.findAll(postRequestedFor(urlPathEqualTo(SEND_PATH)))).isEmpty();
	}

	// ---------------------------------------------------------------- 도우미

	private SolapiClient clientWith(int readTimeout) {
		return new SolapiClient(new SolapiProperties(
				server.baseUrl(), API_KEY, API_SECRET, PF_ID, "0212345678",
				"https://www.moeum.store", null, Map.of(OutboxEventType.ORDER_PAID, TEMPLATE_ID),
				2000, readTimeout));
	}

	private static SolapiSendRequest.Message message() {
		return new SolapiSendRequest.Message("01012341234", "0212345678",
				new SolapiSendRequest.KakaoOption(PF_ID, TEMPLATE_ID,
						Map.of("#{userName}", "김서연", "#{prepayment}", "20000")));
	}

	private static String hmac(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static ResponseDefinitionBuilder json(int status, String body) {
		return WireMock.aResponse()
				.withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}
}
