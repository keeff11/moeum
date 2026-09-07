package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1차금 결제 흐름 (roadmap 4단계 필수 테스트).
 *
 * <b>여기서 지키는 것은 하나다 — 결과를 모르면 아무것도 되돌리지 않는다.</b>
 * 타임아웃·5xx 에서 홀드를 풀면 그 재고가 남에게 팔리고, 실제로 출금됐다면
 * 구매자는 돈만 나간 상태가 된다. 대사 배치가 확정할 때까지 기다려야 한다.
 *
 * point3 는 WireMock 이다. 실제 API 로는 타임아웃과 5xx 를 요청해서 받을 수 없다.
 */
class PaymentFlowTest extends IntegrationTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-000000000000";
	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		POINT3.start();
	}

	@DynamicPropertySource
	static void point3(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
		registry.add("moeum.point3.read-timeout", () -> "500ms");
	}

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentReconcileBatch reconcileBatch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;
	private String sessionToken;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		setup = fixture.saleForm(10, null);
		OrderGroupResponse group = orderService.place(buyer(), order(3));
		sessionToken = group.sessionToken();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 세션 생성

	@Test
	@DisplayName("세션을_만들면_결제창에_필요한_값이_나오고_홀드는_그대로다")
	void 세션_생성() {
		stubCreateSession();

		PaySessionResponse response = paymentService.pay(buyer(), sessionToken);

		assertThat(response.sessionId()).isEqualTo(SESSION_ID);
		assertThat(response.orderToken()).startsWith("ord_");
		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(groupStatus()).containsExactly("PAY_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("홀드가_만료됐으면_세션을_만들지_않는다")
	void 만료된_홀드로는_세션을_못_만든다() {
		expireHolds();

		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);

		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/payment/v3/session")));
	}

	@Test
	@DisplayName("남의_주문으로는_결제할_수_없다")
	void 남의_주문() {
		assertThatThrownBy(() -> paymentService.pay(other(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	// ---------------------------------------------------------------- 승인

	@Test
	@DisplayName("승인이_captured_면_확정되고_재고가_차감된다")
	void 승인_성공() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(held()).isZero();
		assertThat(sold()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("COMMITTED");
	}

	@Test
	@DisplayName("승인_타임아웃이면_홀드를_풀지_않고_CAPTURE_PENDING_을_유지한다")
	void 승인_타임아웃() {
		String orderToken = startPayment();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))
						.withFixedDelay(2000)));

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 출금됐을 수 있다. 여기서 홀드를 풀면 그 재고가 남에게 팔린다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("HELD");
	}

	@Test
	@DisplayName("승인_5xx_면_되돌리지_않는다")
	void 승인_5xx() {
		String orderToken = startPayment();
		stubCapture(500, "{}");

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("승인_4xx_면_즉시_실패_처리하고_홀드를_해제한다")
	void 승인_4xx() {
		String orderToken = startPayment();
		stubCapture(400, """
				{"code":"INVALID_REQUEST"}""");

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 4xx 는 point3 가 받아들이지 않은 것이라 승인이 일어났을 가능성이 없다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(held()).isZero();
		assertThat(sold()).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
	}

	@Test
	@DisplayName("같은_주문에_confirm_이_두_번_와도_한_번만_처리된다")
	void confirm_중복() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		PaymentResultResponse second = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(second.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(sold()).isEqualTo(3);
		// 두 번째는 승인을 부르지 않는다
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("홀드가_만료됐으면_승인을_보내지_않는다")
	void 만료된_홀드로는_승인하지_않는다() {
		String orderToken = startPayment();
		expireHolds();

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);

		// 돈이 나가기 전에 막는다 — 나간 뒤 되돌리는 것보다 항상 싸다
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("세션_id_가_다르면_승인하지_않는다")
	void 세션_불일치() {
		String orderToken = startPayment();

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, "pymt_sess-남의것", null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SESSION_MISMATCH);

		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	// ---------------------------------------------------------------- 대사 배치

	@Test
	@DisplayName("CAPTURE_PENDING_상태에서_배치가_captured_를_확인하면_확정한다")
	void 배치가_확정한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");

		// 실제로는 출금돼 있었다
		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("배치가_두_번_확정해도_재고가_한_번만_차감된다")
	void 배치_멱등() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(sold()).isEqualTo(3);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("CAPTURE_PENDING_인데_세션이_committed_면_승인을_다시_호출한다")
	void 배치가_승인을_재호출한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 구매자는 확정했는데 승인이 닿지 않았다
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("배치가_failed_를_확인하면_실패_확정하고_홀드를_푼다")
	void 배치가_실패를_확정한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubGetSession("""
				{"id":"%s","status":"failed"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(held()).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
	}

	@Test
	@DisplayName("배치_조회가_실패하면_아무것도_확정하지_않는다")
	void 배치_조회_실패() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(503, "{}")));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isZero();
		// 모르는 채로 두는 것이 잘못 확정하는 것보다 낫다
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("방금_시작된_건은_배치가_건드리지_않는다")
	void 갓_시작된_건은_건너뛴다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 실시간 confirm 이 아직 진행 중일 수 있다
		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
	}

	// ---------------------------------------------------------------- 재결제 · 조회

	@Test
	@DisplayName("실패한_뒤에는_같은_행을_재사용해_다시_결제할_수_있다")
	void 재결제() {
		String orderToken = startPayment();
		stubCapture(400, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(paymentStatus()).containsExactly("FAILED");

		// 홀드가 풀렸으니 주문부터 다시 만든다
		fixture.clean();
		setup = fixture.saleForm(10, null);
		sessionToken = orderService.place(buyer(), order(2)).sessionToken();
		stubCreateSession();

		PaySessionResponse retry = paymentService.pay(buyer(), sessionToken);

		assertThat(retry.sessionId()).isEqualTo(SESSION_ID);
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("결과_불명인_주문에_새_세션을_열지_않는다")
	void 진행중이면_새_세션을_막는다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 여기서 새 세션을 열어주면 이중 결제가 된다
		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
	}

	@Test
	@DisplayName("상태_조회는_부작용이_없다")
	void 상태_조회() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		PaymentResultResponse first = paymentService.status(buyer(), orderToken);
		PaymentResultResponse second = paymentService.status(buyer(), orderToken);

		assertThat(first.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(second.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		// 조회가 승인을 부르면 조회할 때마다 결제가 일어나는 API 가 된다
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(held()).isEqualTo(3);
	}

	// ---------------------------------------------------------------- 도우미

	private String startPayment() {
		stubCreateSession();
		return paymentService.pay(buyer(), sessionToken).orderToken();
	}

	private void stubCreateSession() {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
	}

	private void stubCapture(int status, String body) {
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(status, body)));
	}

	private void stubGetSession(String body) {
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	/** 배치의 유예 시간(1분)을 지나게 만든다 */
	private void agePending() {
		jdbcTemplate.update("UPDATE payment SET updated_at = DATE_SUB(NOW(6), INTERVAL 5 MINUTE)");
	}

	private void expireHolds() {
		jdbcTemplate.update("UPDATE stock_hold SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private static SessionUser other() {
		return new SessionUser("kakao-stranger", "남");
	}

	private OrderCreateRequest order(int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)));
	}

	private List<String> paymentStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM payment", String.class);
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}

	private List<String> holdStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM stock_hold", String.class);
	}

	private int held() {
		return jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}

	private int sold() {
		return jdbcTemplate.queryForObject(
				"SELECT sold FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}
}
