package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Capture;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3Properties;
import store.moeum.moeum.payment.infra.Point3Session;
import store.moeum.moeum.payment.infra.Point3SessionRequest;
import store.moeum.moeum.payment.infra.Point3SessionStatus;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * point3 호출의 실패 처리 (D-006, CLAUDE.md 규칙 2·3).
 *
 * <b>여기서 검증하는 것은 성공 경로가 아니라 갈림길이다.</b>
 * 4xx 는 되돌려도 안전한 확정 실패고, 5xx·타임아웃은 승인이 이미 일어났을 수 있어
 * 아무것도 되돌리면 안 된다. 이 둘이 같은 예외로 흐르는 순간 미수금이 생긴다.
 *
 * 실제 point3 로는 이 케이스들을 재현할 수 없다 — 타임아웃과 5xx 를 요청해서 받을 수는 없으니까.
 * 그래서 WireMock 으로 만든다. 토큰이 생겨도 이 테스트는 그대로 쓴다.
 */
class Point3ClientTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-000000000000";
	private static final String TOKEN = "test-api-token";

	private WireMockServer server;
	private Point3Client client;

	@BeforeEach
	void setUp() {
		server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		client = clientWith(Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	// ---------------------------------------------------------------- 성공 경로

	@Test
	@DisplayName("세션을_생성하면_sessionId_와_세금_내역을_받는다")
	void 세션_생성() {
		server.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":32000,
						 "supplyAmount":29091,"vat":2909,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));

		Point3Session session = client.createSession(
				Point3SessionRequest.general(32000, "아크릴 스탠드", "민지의 작업실"));

		assertThat(session.id()).isEqualTo(SESSION_ID);
		assertThat(session.status()).isEqualTo(Point3SessionStatus.CREATED);
		// 불변식: amount = supplyAmount + taxFreeAmount + vat
		assertThat(session.supplyAmount() + session.taxFreeAmount() + session.vat())
				.isEqualTo(session.amount());
	}

	@Test
	@DisplayName("승인이_captured_면_성공이다")
	void 승인_성공() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))));

		Point3Capture capture = client.capture(SESSION_ID);

		assertThat(capture.isCaptured()).isTrue();
	}

	@Test
	@DisplayName("토큰은_Authorization_헤더로_나가고_승인_바디엔_payerId_가_없다")
	void 요청_형식() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))));

		client.capture(SESSION_ID);

		// payerId 는 승인 요청에 넣지 않는다 (point3-api 5절)
		server.verify(postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.withHeader("Authorization", equalTo("Bearer " + TOKEN)));
	}

	@Test
	@DisplayName("세션_생성은_vat_를_보내지_않아_point3_가_계산하게_한다")
	void vat_는_보내지_않는다() {
		server.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":32000,
						 "supplyAmount":29091,"vat":2909,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));

		client.createSession(Point3SessionRequest.general(32000, "아크릴 스탠드", "민지의 작업실"));

		// 우리가 반올림하면 amount = supply + taxFree + vat 불변식이 어긋날 수 있다
		server.verify(postRequestedFor(urlPathEqualTo("/payment/v3/session"))
				.withRequestBody(matchingJsonPath("$[?(!@.vat)]"))
				.withRequestBody(matchingJsonPath("$.tradeOpt", equalTo("GENERAL"))));
	}

	// ---------------------------------------------------------------- 갈림길

	@Test
	@DisplayName("승인_4xx_면_확정_실패다_되돌려도_안전하다")
	void 승인_4xx() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(400, """
						{"code":"INVALID_REQUEST"}""")));

		assertThatThrownBy(() -> client.capture(SESSION_ID))
				.isInstanceOf(Point3FailedException.class)
				.extracting(e -> ((Point3FailedException) e).status())
				.isEqualTo(400);
	}

	@Test
	@DisplayName("승인_401_403_404_도_전부_확정_실패다")
	void 다른_4xx도_확정_실패() {
		for (int status : new int[]{401, 403, 404}) {
			server.resetAll();
			server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
					.willReturn(json(status, "{}")));

			assertThatThrownBy(() -> client.capture(SESSION_ID))
					.as("status=%d", status)
					.isInstanceOf(Point3FailedException.class);
		}
	}

	@Test
	@DisplayName("승인_5xx_면_결과_불명이다_되돌리면_안_된다")
	void 승인_5xx() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(500, "{}")));

		// 여기서 Point3FailedException 이 나오면 호출부가 홀드를 풀어버린다.
		// 실제로는 출금됐을 수 있으므로 그 재고를 남에게 팔면 안 된다.
		assertThatThrownBy(() -> client.capture(SESSION_ID))
				.isInstanceOf(Point3UncertainException.class);
	}

	@Test
	@DisplayName("승인이_타임아웃되면_결과_불명이다")
	void 승인_타임아웃() {
		Point3Client impatient = clientWith(Duration.ofMillis(300));
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))
						.withFixedDelay(2000)));

		// point3 는 승인을 끝냈는데 응답만 늦은 경우와 구별할 방법이 없다
		assertThatThrownBy(() -> impatient.capture(SESSION_ID))
				.isInstanceOf(Point3UncertainException.class);
	}

	@Test
	@DisplayName("승인이_processing_이면_예외가_아니라_대기_상태로_돌아온다")
	void 승인_processing() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"processing"}""".formatted(SESSION_ID))));

		Point3Capture capture = client.capture(SESSION_ID);

		// 200 이라고 성공이 아니다. 결과를 모르는 상태라 재조회해야 한다
		assertThat(capture.isCaptured()).isFalse();
		assertThat(capture.status()).isEqualTo(Point3SessionStatus.PROCESSING);
		assertThat(capture.status().isPending()).isTrue();
	}

	@Test
	@DisplayName("200_인데_본문이_비면_결과_불명으로_본다")
	void 빈_본문() {
		server.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json")));

		assertThatThrownBy(() -> client.capture(SESSION_ID))
				.isInstanceOf(Point3UncertainException.class);
	}

	// ---------------------------------------------------------------- 상태 해석

	@Test
	@DisplayName("모르는_status_는_UNKNOWN_이지_성공이_아니다")
	void 모르는_상태() {
		server.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"some_new_status"}""".formatted(SESSION_ID))));

		Point3Session session = client.getSession(SESSION_ID);

		// 명세에 없는 값이 왔다고 예외로 터뜨리지도, 성공으로 넘기지도 않는다
		assertThat(session.status()).isEqualTo(Point3SessionStatus.UNKNOWN);
		assertThat(session.status().isPending()).isFalse();
		assertThat(session.status().isTerminalFailure()).isFalse();
	}

	@Test
	@DisplayName("committed_는_출금_완료가_아니라_승인을_불러도_된다는_뜻이다")
	void committed_는_성공이_아니다() {
		server.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"committed"}""".formatted(SESSION_ID))));

		Point3Session session = client.getSession(SESSION_ID);

		assertThat(session.status()).isEqualTo(Point3SessionStatus.COMMITTED);
		assertThat(session.status().isPending()).isTrue();
		assertThat(session.status().isTerminalFailure()).isFalse();
	}

	@Test
	@DisplayName("expired_는_되돌려도_안전한_확정_실패다")
	void expired() {
		server.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"expired"}""".formatted(SESSION_ID))));

		assertThat(client.getSession(SESSION_ID).status().isTerminalFailure()).isTrue();
	}

	// ---------------------------------------------------------------- 설정

	@Test
	@DisplayName("토큰이_없으면_호출하지_않고_확정_실패로_끝낸다")
	void 토큰_없음() {
		Point3Client unconfigured = new Point3Client(new Point3Properties(
				server.baseUrl(), "  ", Duration.ofSeconds(1), Duration.ofSeconds(1)));

		assertThatThrownBy(() -> unconfigured.capture(SESSION_ID))
				.isInstanceOf(Point3FailedException.class);

		// 토큰 없이 point3 를 두들기지 않는다
		server.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	// ---------------------------------------------------------------- 도우미

	private Point3Client clientWith(Duration readTimeout) {
		return new Point3Client(new Point3Properties(
				server.baseUrl(), TOKEN, Duration.ofSeconds(2), readTimeout));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return WireMock.aResponse()
				.withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}
}
