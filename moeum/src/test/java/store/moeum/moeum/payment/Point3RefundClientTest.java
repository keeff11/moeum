package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.payment.exception.Point3RefundConflict;
import store.moeum.moeum.payment.exception.Point3RefundRejected;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3Properties;
import store.moeum.moeum.payment.infra.Point3RefundEntry;
import store.moeum.moeum.payment.infra.Point3RefundRequest;
import store.moeum.moeum.payment.infra.Point3RefundStatus;
import store.moeum.moeum.payment.infra.RefundConflictCode;
import store.moeum.moeum.payment.infra.RefundEntryStatus;
import store.moeum.moeum.payment.infra.RefundSessionStatus;

import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 취소 호출의 분기 (point3-api 8절).
 *
 * <b>승인과 결정적으로 다른 점은 취소가 멱등하지 않다는 것이다.</b>
 * 타임아웃 뒤에 다시 보내면 같은 취소가 두 번 실행된다. 그래서 409 여섯 개를
 * "확정 거절" 과 "모름" 으로 나누는 것이 이 계층의 존재 이유다 —
 * '모름' 을 확정 거절로 잘못 다루면 실제로 환불된 건의 재고를 되돌리게 된다.
 */
class Point3RefundClientTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-000000000000";
	private static final String KEY = "refund-key-0001";

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

	// ---------------------------------------------------------------- 성공

	@Test
	@DisplayName("취소가_200이면_항상_completed_다")
	void 취소_성공() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"ref-0001","paymentSessionId":"%s","status":"completed",
						 "amount":10000,"taxFreeAmount":0,"vat":909,"fee":100,"reason":"고객 요청"}
						""".formatted(SESSION_ID))));

		Point3RefundEntry entry = client.refund(SESSION_ID, request(10000, 909), KEY);

		// POST 200 의 status enum 은 completed 하나뿐이다. processing 인 채로 200 이 오지 않는다
		assertThat(entry.isCompleted()).isTrue();
		assertThat(entry.id()).isEqualTo("ref-0001");
		// 취소마다 수수료가 붙는다. 셀러 정산에 영향이 있어 저장해야 한다
		assertThat(entry.fee()).isEqualTo(100);
	}

	@Test
	@DisplayName("Idempotency_Key_가_헤더로_나가고_세금_3종이_바디에_담긴다")
	void 요청_형식() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"ref-0001","status":"completed","amount":10000,"vat":909,"fee":0}""")));

		client.refund(SESSION_ID, request(10000, 909), KEY);

		// 세금을 point3 가 계산하지 않으므로 우리가 보내야 한다
		server.verify(postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.withHeader("Idempotency-Key", equalTo(KEY))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
						.matchingJsonPath("$.refundVat", equalTo("909"))));
	}

	// ---------------------------------------------------------------- 409 · 확정 거절

	@Test
	@DisplayName("정산됐거나_취소_불가_상태면_확정_거절이다")
	void 확정_거절_코드() {
		for (String code : new String[]{
				"SETTLEMENT_DEADLINE_EXCEEDED", "REFUND_NOT_IN_REFUNDABLE_STATE", "EOB_WINDOW_BLOCKED"}) {
			server.resetAll();
			stubConflict(code);

			Point3RefundConflict thrown = catchConflict();

			assertThat(thrown.code().name()).as(code).isEqualTo(code);
			// 취소가 일어나지 않았다. 실패로 확정하고 되돌려도 안전하다
			assertThat(thrown.code().isConfirmedRejection()).as(code).isTrue();
			assertThat(thrown.code().needsStatusCheck()).as(code).isFalse();
		}
	}

	@Test
	@DisplayName("EOB_차단만_잠시_뒤_재시도_대상이다")
	void EOB_는_나중에_재시도() {
		assertThat(RefundConflictCode.EOB_WINDOW_BLOCKED.isRetryableLater()).isTrue();
		assertThat(RefundConflictCode.SETTLEMENT_DEADLINE_EXCEEDED.isRetryableLater()).isFalse();
		assertThat(RefundConflictCode.REFUND_NOT_IN_REFUNDABLE_STATE.isRetryableLater()).isFalse();
	}

	// ---------------------------------------------------------------- 409 · 모름

	@Test
	@DisplayName("미확정_중복_진행중은_조회로_확인해야_한다")
	void 모름_코드() {
		for (String code : new String[]{
				"REFUND_TEMPORARY_UNAVAILABLE", "REFUND_DUPLICATE_REQUEST", "REFUND_ACTIVE_REQUEST_EXISTS"}) {
			server.resetAll();
			stubConflict(code);

			Point3RefundConflict thrown = catchConflict();

			// 여기서 새 키로 다시 보내면 같은 취소가 두 번 실행된다
			assertThat(thrown.code().needsStatusCheck()).as(code).isTrue();
			assertThat(thrown.code().isConfirmedRejection()).as(code).isFalse();
		}
	}

	@Test
	@DisplayName("모르는_409_코드는_확정_거절이_아니라_모름으로_다룬다")
	void 모르는_코드는_모름이다() {
		stubConflict("SOME_NEW_CODE");

		Point3RefundConflict thrown = catchConflict();

		// 확정 거절로 다루면 실제로 환불된 건의 재고를 되돌리게 된다
		assertThat(thrown.code()).isEqualTo(RefundConflictCode.UNKNOWN);
		assertThat(thrown.code().needsStatusCheck()).isTrue();
	}

	@Test
	@DisplayName("409_본문을_읽지_못해도_모름으로_다룬다")
	void 본문을_못_읽으면_모름() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(409, "본문이 JSON 이 아니다")));

		assertThat(catchConflict().code()).isEqualTo(RefundConflictCode.UNKNOWN);
	}

	// ---------------------------------------------------------------- 그 밖의 실패

	@Test
	@DisplayName("422는_명시적_거절이라_되돌려도_안전하다")
	void 거절_422() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(422, """
						{"status":422,"result":{"code":"INVALID_AMOUNT"}}""")));

		assertThatThrownBy(() -> client.refund(SESSION_ID, request(10000, 909), KEY))
				.isInstanceOf(Point3RefundRejected.class)
				.extracting(e -> ((Point3RefundRejected) e).status())
				.isEqualTo(422);
	}

	@Test
	@DisplayName("400_401_404는_연동_오류로_구분된다")
	void 연동_오류() {
		for (int status : new int[]{400, 401, 404}) {
			server.resetAll();
			server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
					.willReturn(json(status, "{}")));

			assertThatThrownBy(() -> client.refund(SESSION_ID, request(10000, 909), KEY))
					.as("status=%d", status)
					.isInstanceOf(Point3RefundRejected.class)
					.matches(e -> ((Point3RefundRejected) e).isIntegrationError());
		}
	}

	@Test
	@DisplayName("취소가_타임아웃되면_결과_불명이다")
	void 취소_타임아웃() {
		Point3Client impatient = clientWith(Duration.ofMillis(300));
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"ref-0001","status":"completed","amount":10000}""")
						.withFixedDelay(2000)));

		// 승인과 달리 여기서 재요청하면 두 번 환불된다. 조회로만 확인해야 한다
		assertThatThrownBy(() -> impatient.refund(SESSION_ID, request(10000, 909), KEY))
				.isInstanceOf(Point3UncertainException.class);
	}

	@Test
	@DisplayName("취소_5xx도_결과_불명이다")
	void 취소_5xx() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(503, "{}")));

		assertThatThrownBy(() -> client.refund(SESSION_ID, request(10000, 909), KEY))
				.isInstanceOf(Point3UncertainException.class);
	}

	// ---------------------------------------------------------------- 조회

	@Test
	@DisplayName("조회는_취소_가능_금액과_새_취소_가능_여부를_준다")
	void 조회() {
		server.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"partiallyRefunded",
						 "originalAmount":10000,"refundableAmount":5000,
						 "canCreateRefund":true,
						 "refunds":[{"id":"ref-1","status":"completed","amount":5000,"vat":455,"fee":100}]}
						""".formatted(SESSION_ID))));

		Point3RefundStatus status = client.getRefund(SESSION_ID).orElseThrow();

		// 부분 취소가 완료되면 status 는 refundable 이 아니다. canCreateRefund 로 판단해야 한다
		assertThat(status.status()).isEqualTo(RefundSessionStatus.PARTIALLY_REFUNDED);
		assertThat(status.canCreate()).isTrue();
		assertThat(status.refundable()).isEqualTo(5000);
		assertThat(status.hasProcessing()).isFalse();
	}

	@Test
	@DisplayName("조회_404는_오류가_아니라_취소_이력_없음이다")
	void 조회_404() {
		server.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(404, """
						{"status":404,"result":{"code":"NOT_FOUND"}}""")));

		// 예외로 던지면 취소 한 번도 안 한 결제는 취소가 아예 안 된다
		Optional<Point3RefundStatus> status = client.getRefund(SESSION_ID);

		assertThat(status).isEmpty();
	}

	@Test
	@DisplayName("processing_항목이_있으면_resume_대상으로_잡힌다")
	void 진행중_감지() {
		server.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"processing",
						 "originalAmount":10000,"refundableAmount":0,"canCreateRefund":false,
						 "refunds":[{"id":"ref-1","status":"processing","amount":10000,
						             "failure":{"code":"refundMethodDeclined","message":"거절"}}]}
						""".formatted(SESSION_ID))));

		Point3RefundStatus status = client.getRefund(SESSION_ID).orElseThrow();

		assertThat(status.hasProcessing()).isTrue();
		assertThat(status.canCreate()).isFalse();
		assertThat(status.refunds().get(0).status()).isEqualTo(RefundEntryStatus.PROCESSING);
		assertThat(status.refunds().get(0).failure().code()).isEqualTo("refundMethodDeclined");
	}

	@Test
	@DisplayName("canCreateRefund_가_없으면_만들지_않는다")
	void 값이_없으면_보수적으로() {
		server.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"refundable"}""".formatted(SESSION_ID))));

		Point3RefundStatus status = client.getRefund(SESSION_ID).orElseThrow();

		// 모르면 안 하는 쪽이 안전하다
		assertThat(status.canCreate()).isFalse();
		assertThat(status.refunds()).isEmpty();
	}

	// ---------------------------------------------------------------- 재개

	@Test
	@DisplayName("재개는_최신_취소_상태를_돌려준다")
	void 재개() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID + "/resume"))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"fullyRefunded",
						 "originalAmount":10000,"refundableAmount":0,"canCreateRefund":false,
						 "refunds":[{"id":"ref-1","status":"completed","amount":10000}]}
						""".formatted(SESSION_ID))));

		Point3RefundStatus status = client.resumeRefund(SESSION_ID);

		// 재개 전에 이미 끝났어도 사고가 없다. 여러 번 불러도 안전하다
		assertThat(status.status()).isEqualTo(RefundSessionStatus.FULLY_REFUNDED);
		assertThat(status.hasProcessing()).isFalse();
	}

	@Test
	@DisplayName("재개가_5xx면_결과_불명이다")
	void 재개_5xx() {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID + "/resume"))
				.willReturn(json(503, "{}")));

		assertThatThrownBy(() -> client.resumeRefund(SESSION_ID))
				.isInstanceOf(Point3UncertainException.class);
	}

	// ---------------------------------------------------------------- 도우미

	private Point3RefundConflict catchConflict() {
		try {
			client.refund(SESSION_ID, request(10000, 909), KEY);
			throw new AssertionError("409 가 나와야 한다");
		} catch (Point3RefundConflict e) {
			return e;
		}
	}

	private void stubConflict(String code) {
		server.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(409, """
						{"status":409,"timestamp":"2026-09-07T00:00:00Z","result":{"code":"%s"}}
						""".formatted(code))));
	}

	private static Point3RefundRequest request(int amount, int vat) {
		return new Point3RefundRequest(amount, 0, vat, "고객 요청");
	}

	private Point3Client clientWith(Duration readTimeout) {
		return new Point3Client(new Point3Properties(
				server.baseUrl(), "test-token", "client-test", Duration.ofSeconds(2), readTimeout));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}
}
