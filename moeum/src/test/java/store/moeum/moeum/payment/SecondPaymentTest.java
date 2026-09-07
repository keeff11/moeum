package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.saleform.SaleFormService;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2차금 흐름 (roadmap 5단계).
 *
 * <b>확인하려는 것은 "새로 짜지 않았다" 는 것이다.</b> 1차금과 같은 PaymentService 를 타고
 * phase 만 다르다. 승인 결과를 다루는 규칙(4xx/5xx/타임아웃)은 그대로 재사용된다.
 *
 * 다른 점은 둘뿐이다 — 홀드가 없고, 모든 폼이 입고돼야 열린다.
 */
class SecondPaymentTest extends IntegrationTest {

	/** 1차금·2차금이 각자 세션을 갖는다. uk_payment_session 이 세션당 결제 하나만 허용한다 */
	private static final String FIRST_SESSION = "pymt_sess-first-0000-0000-000000000001";
	private static final String SESSION_ID = "pymt_sess-second-0000-0000-000000000002";
	private static final String PAYER_ID = "payer:01JABCDEFGHIJKLMNOPQRSTUV";
	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		POINT3.start();
	}

	@DynamicPropertySource
	static void point3(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
	}

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;
	private String sellerKakaoId;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		setup = fixture.saleForm(10, null);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
	}

	// ---------------------------------------------------------------- 입고 전

	@Test
	@DisplayName("입고_전에는_2차금을_결제할_수_없다")
	void 입고_전에는_못_한다() {
		String orderToken = payFirst(3);

		assertThatThrownBy(() -> paymentService.paySecond(buyer(), orderToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SECOND_PAYMENT_NOT_DUE);
	}

	@Test
	@DisplayName("1차금이_안_끝난_주문은_입고_처리되지_않는다")
	void 미결제_주문은_입고_대상이_아니다() {
		orderService.place(buyer(), order(setup.optionId(), 2));

		// 돈을 안 받은 주문을 입고 처리하면 2차금 청구 대상에 섞여 잔금만 청구하게 된다
		assertThat(saleFormService.markArrived(sellerKakaoId, setup.saleFormId())).isZero();
		assertThat(orderStatuses()).containsOnly("CREATED");
	}

	@Test
	@DisplayName("한_폼만_입고되면_묶음_전체는_아직_청구할_수_없다")
	void 일부만_입고되면_안_된다() {
		OrderFixture.Setup second = fixture.saleFormOfSameSeller(setup, 10);
		String orderToken = payFirst(List.of(
				new OrderCreateRequest.Item(setup.optionId(), 2),
				new OrderCreateRequest.Item(second.optionId(), 1)));

		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());

		// 배송비가 묶음당 1회라 일부만 입고됐다고 청구하면 배송비를 나눌 방법이 없다
		assertThatThrownBy(() -> paymentService.paySecond(buyer(), orderToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SECOND_PAYMENT_NOT_DUE);

		saleFormService.markArrived(sellerKakaoId, second.saleFormId());
		stubCreateSession();
		assertThat(paymentService.paySecond(buyer(), orderToken).sessionId()).isEqualTo(SESSION_ID);
	}

	// ---------------------------------------------------------------- 2차금 결제

	@Test
	@DisplayName("모든_폼이_입고되면_2차금_세션이_열리고_payerId_가_함께_나간다")
	void 이차금_세션() {
		String orderToken = payFirstWithPayerId(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();

		PaySessionResponse response = paymentService.paySecond(buyer(), orderToken);

		assertThat(response.sessionId()).isEqualTo(SESSION_ID);
		// SDK 의 customerKey 로 넘기면 인증 단계가 줄어든다 (point3-api 6절)
		assertThat(response.payerId()).isEqualTo(PAYER_ID);
		// 2차금 = 잔금 + 배송비. 옵션 2차금 12000 × 3 + 배송비 3000
		assertThat(response.amount()).isEqualTo(39000);
		assertThat(groupStatus()).containsExactly("SECOND_PENDING");
		assertThat(paymentPhases()).containsExactlyInAnyOrder("FIRST", "SECOND");
	}

	@Test
	@DisplayName("2차금이_확정되면_SECOND_PAID_가_되고_재고는_건드리지_않는다")
	void 이차금_확정() {
		String orderToken = payFirstWithPayerId(3);
		int soldAfterFirst = sold();
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();
		paymentService.paySecond(buyer(), orderToken);
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		PaymentResultResponse result = paymentService.confirmSecond(buyer(), orderToken, SESSION_ID);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(groupStatus()).containsExactly("SECOND_PAID");
		// 재고는 1차금에서 이미 확정됐다. 2차금이 또 건드리면 이중 차감이다
		assertThat(sold()).isEqualTo(soldAfterFirst);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("2차금_승인이_타임아웃돼도_1차금_확정은_그대로다")
	void 이차금_결과_불명() {
		String orderToken = payFirstWithPayerId(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();
		paymentService.paySecond(buyer(), orderToken);
		stubCapture(500, "{}");

		PaymentResultResponse result = paymentService.confirmSecond(buyer(), orderToken, SESSION_ID);

		// 1차금과 같은 규칙을 탄다 — 모르면 되돌리지 않는다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(secondPaymentStatus()).isEqualTo("CAPTURE_PENDING");
		assertThat(groupStatus()).containsExactly("SECOND_PENDING");
	}

	@Test
	@DisplayName("2차금이_실패해도_묶음을_FAILED_로_내리지_않는다")
	void 이차금_실패는_묶음을_죽이지_않는다() {
		String orderToken = payFirstWithPayerId(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();
		paymentService.paySecond(buyer(), orderToken);
		stubCapture(400, "{}");

		PaymentResultResponse result = paymentService.confirmSecond(buyer(), orderToken, SESSION_ID);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		assertThat(secondPaymentStatus()).isEqualTo("FAILED");
		// 1차금은 이미 받았고 상품도 나갔다. 미수로 남겨 두고 재청구한다
		assertThat(groupStatus()).containsExactly("SECOND_PENDING");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("2차금도_실패한_행을_재사용해_다시_결제할_수_있다")
	void 이차금_재결제() {
		String orderToken = payFirstWithPayerId(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();
		paymentService.paySecond(buyer(), orderToken);
		stubCapture(400, "{}");
		paymentService.confirmSecond(buyer(), orderToken, SESSION_ID);

		PaySessionResponse retry = paymentService.paySecond(buyer(), orderToken);

		assertThat(retry.sessionId()).isEqualTo(SESSION_ID);
		assertThat(secondPaymentStatus()).isEqualTo("CREATED");
		assertThat(paymentPhases()).containsExactlyInAnyOrder("FIRST", "SECOND");
	}

	@Test
	@DisplayName("이미_2차금까지_끝났으면_다시_열지_않는다")
	void 중복_이차금() {
		String orderToken = payFirstWithPayerId(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();
		paymentService.paySecond(buyer(), orderToken);
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		paymentService.confirmSecond(buyer(), orderToken, SESSION_ID);

		assertThatThrownBy(() -> paymentService.paySecond(buyer(), orderToken))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("이미 2차금 결제가 완료");
	}

	// ---------------------------------------------------------------- payerId

	@Test
	@DisplayName("payerId_는_이미_있으면_덮어쓰지_않는다")
	void payerId_는_덮어쓰지_않는다() {
		payFirstWithPayerId(2);
		assertThat(payerId()).isEqualTo(PAYER_ID);

		// 같은 구매자의 두 번째 주문에서 다른 값이 와도 바꾸지 않는다 (point3-api 5절).
		// clean() 은 buyer 까지 지우므로 폼만 새로 만든다
		setup = fixture.saleForm(10, null);
		payFirst(2, "payer:DIFFERENT0000000000000000", "pymt_sess-first-0000-0000-000000000009");

		assertThat(payerId()).isEqualTo(PAYER_ID);
	}

	@Test
	@DisplayName("payerId_가_없으면_null_로_내려가고_결제는_그대로_된다")
	void payerId_없이도_된다() {
		String orderToken = payFirst(3);
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		stubCreateSession();

		// 프론트가 ANONYMOUS 로 진행한다
		assertThat(paymentService.paySecond(buyer(), orderToken).payerId()).isNull();
	}

	// ---------------------------------------------------------------- 입고 권한

	@Test
	@DisplayName("남의_폼은_입고_처리할_수_없다")
	void 남의_폼_입고() {
		payFirst(3);

		assertThatThrownBy(() -> saleFormService.markArrived("kakao-not-seller", setup.saleFormId()))
				.isInstanceOf(BusinessException.class);
	}

	// ---------------------------------------------------------------- 도우미

	private String payFirst(int qty) {
		return payFirst(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)), null);
	}

	private String payFirst(int qty, String payerId) {
		return payFirst(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)), payerId, FIRST_SESSION);
	}

	/** 같은 테스트에서 주문을 두 번 만들 때는 세션을 나눠야 한다 — uk_payment_session */
	private String payFirst(int qty, String payerId, String sessionId) {
		return payFirst(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)), payerId, sessionId);
	}

	private String payFirstWithPayerId(int qty) {
		return payFirst(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)), PAYER_ID);
	}

	private String payFirst(List<OrderCreateRequest.Item> items) {
		return payFirst(items, null);
	}

	private String payFirst(List<OrderCreateRequest.Item> items, String payerId) {
		return payFirst(items, payerId, FIRST_SESSION);
	}

	private String payFirst(List<OrderCreateRequest.Item> items, String payerId, String firstSession) {
		String sessionToken = orderService.place(buyer(), new OrderCreateRequest(items)).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(firstSession))));
		String orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + firstSession))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(firstSession))));
		paymentService.confirm(buyer(), orderToken, firstSession, payerId);
		POINT3.resetAll();
		return orderToken;
	}

	private void stubCreateSession() {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":39000,
						 "supplyAmount":35455,"vat":3545,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
	}

	private void stubCapture(int status, String body) {
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(status, body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-second-payer", "2차금 구매자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}

	private List<String> orderStatuses() {
		return jdbcTemplate.queryForList("SELECT status FROM orders", String.class);
	}

	private List<String> paymentPhases() {
		return jdbcTemplate.queryForList("SELECT phase FROM payment", String.class);
	}

	private String secondPaymentStatus() {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM payment WHERE phase = 'SECOND'", String.class);
	}

	private String payerId() {
		return jdbcTemplate.queryForObject(
				"SELECT payer_id FROM buyer WHERE kakao_id = ?", String.class, "kakao-second-payer");
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
