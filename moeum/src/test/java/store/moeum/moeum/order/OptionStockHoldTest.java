package store.moeum.moeum.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import store.moeum.moeum.cart.CartService;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.payment.refund.OrderRefundService;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 옵션별 재고 (D-054) — 확보 · 해제 · 확정 · 되돌림이 폼과 옵션 두 층을 같이 움직이는가.
 *
 * 옵션 A 2개 · 옵션 B 5개인 단독 판매 폼. 폼 재고는 7이다.
 * 옵션 A 가 다 나가도 폼 재고가 남아 있는 상황이 이 기능의 존재 이유라서 그 경우를 본다.
 */
@Import(OptionStockHoldTest.FixedClockConfig.class)
class OptionStockHoldTest extends IntegrationTest {

	/** 낮 12시로 고정한다. 취소 테스트가 밤 11시 반에 돌면 EOB 차단으로 깨진다 (OrderRefundApiTest 와 같다) */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			ZoneId kst = ZoneId.of("Asia/Seoul");
			return Clock.fixed(LocalDateTime.of(2026, 9, 8, 12, 0).atZone(kst).toInstant(), kst);
		}
	}

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-00000000opt1";
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
	private OrderRefundService orderRefundService;

	@Autowired
	private CartService cartService;

	@Autowired
	private HoldExpiryBatch batch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		fixture.buyerWithAddress("kakao-opt-payer", "옵션구매자");
		setup = fixture.soloSaleFormWithOptionStock(2, 5);
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 확보

	@Test
	@DisplayName("홀드는_폼과_옵션_두_층을_같이_올린다")
	void 홀드() {
		orderService.place(buyer("kakao-a"), order(setup.optionId(), 2));

		assertThat(formHeld()).isEqualTo(2);
		assertThat(optionHeld(setup.optionId())).isEqualTo(2);
		assertThat(optionHeld(setup.secondOptionId())).isZero();
	}

	@Test
	@DisplayName("옵션_A_가_다_나가면_폼_재고가_남아도_A_는_품절이고_B_는_살_수_있다")
	void 옵션_품절() {
		orderService.place(buyer("kakao-a"), order(setup.optionId(), 2));

		assertThatThrownBy(() -> orderService.place(buyer("kakao-b"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class)
				.hasMessageContaining("옵션 A");

		// 실패한 주문은 폼 홀드까지 같이 롤백돼야 한다 — 아니면 폼 재고만 새어 나간다
		assertThat(formHeld()).isEqualTo(2);

		OrderGroupResponse response = orderService.place(buyer("kakao-c"), order(setup.secondOptionId(), 5));
		assertThat(response.sessionToken()).startsWith("cs_");
		assertThat(formHeld()).isEqualTo(7);
		assertThat(optionHeld(setup.secondOptionId())).isEqualTo(5);
	}

	@Test
	@DisplayName("한_묶음에_옵션_A_B_를_같이_담아도_옵션마다_판정한다")
	void 묶음_판정() {
		assertThatThrownBy(() -> orderService.place(buyer("kakao-a"), new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(setup.optionId(), 3),
				new OrderCreateRequest.Item(setup.secondOptionId(), 1)))))
				.isInstanceOf(OutOfStockException.class);

		assertThat(formHeld()).isZero();
		assertThat(optionHeld(setup.optionId())).isZero();
		assertThat(optionHeld(setup.secondOptionId())).isZero();
	}

	// ---------------------------------------------------------------- 해제

	@Test
	@DisplayName("이탈_해제는_옵션_held_도_되돌린다")
	void 해제() {
		SessionUser user = buyer("kakao-leave");
		OrderGroupResponse response = orderService.place(user, order(setup.optionId(), 2));

		orderService.release(user, response.sessionToken());

		assertThat(formHeld()).isZero();
		assertThat(optionHeld(setup.optionId())).isZero();

		// 풀린 옵션 재고를 다른 구매자가 가져갈 수 있다
		orderService.place(buyer("kakao-next"), order(setup.optionId(), 2));
		assertThat(optionHeld(setup.optionId())).isEqualTo(2);
	}

	@Test
	@DisplayName("만료_배치도_옵션_held_를_되돌리고_두_번_돌아도_한_번만_돌린다")
	void 만료_배치() {
		orderService.place(buyer("kakao-ghost"), order(setup.optionId(), 2));
		jdbcTemplate.update("UPDATE stock_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE");

		assertThat(batch.expireOnce()).isEqualTo(1);
		assertThat(optionHeld(setup.optionId())).isZero();

		assertThat(batch.expireOnce()).isZero();
		assertThat(optionHeld(setup.optionId())).isZero();
		assertThat(formHeld()).isZero();
	}

	// ---------------------------------------------------------------- 확정 · 되돌림

	@Test
	@DisplayName("결제가_확정되면_옵션_held_가_sold_로_옮겨간다")
	void 확정() {
		payFor(setup.optionId(), 2);

		assertThat(optionHeld(setup.optionId())).isZero();
		assertThat(optionSold(setup.optionId())).isEqualTo(2);
		assertThat(formSold()).isEqualTo(2);
	}

	@Test
	@DisplayName("취소하면_옵션_sold_도_되돌아간다")
	void 취소_되돌림() {
		String orderToken = payFor(setup.optionId(), 2);
		stubRefundable();
		stubRefundOk();

		Long orderId = jdbcTemplate.queryForObject("SELECT id FROM orders", Long.class);
		orderRefundService.refund(payer(), orderToken, orderId, "단순 변심");

		assertThat(optionSold(setup.optionId())).isZero();
		assertThat(formSold()).isZero();
	}

	// ---------------------------------------------------------------- 장바구니

	@Test
	@DisplayName("장바구니_상태는_옵션_재고로_판정한다")
	void 장바구니() {
		orderService.place(buyer("kakao-a"), order(setup.optionId(), 2));

		SessionUser shopper = buyer("kakao-shopper");
		cartService.add(shopper, new CartAddRequest(setup.optionId(), 1));
		cartService.add(shopper, new CartAddRequest(setup.secondOptionId(), 1));

		List<CartResponse.CartItemResponse> items = cartService.findMine(shopper).get(0).items();

		CartResponse.CartItemResponse itemA = items.stream()
				.filter(item -> item.optionId().equals(setup.optionId())).findFirst().orElseThrow();
		CartResponse.CartItemResponse itemB = items.stream()
				.filter(item -> item.optionId().equals(setup.secondOptionId())).findFirst().orElseThrow();

		assertThat(itemA.remainingStock()).isZero();
		assertThat(itemA.status()).isEqualTo(CartResponse.ItemStatus.SOLD_OUT);
		assertThat(itemB.remainingStock()).isEqualTo(5);
		assertThat(itemB.status()).isEqualTo(CartResponse.ItemStatus.AVAILABLE);
	}

	// ---------------------------------------------------------------- 결제 헬퍼

	private String payFor(Long optionId, int qty) {
		String sessionToken = orderService.place(payer(), order(optionId, qty)).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
		String orderToken = paymentService.pay(payer(), sessionToken).orderToken();

		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))));
		PaymentResultResponse result = paymentService.confirm(payer(), orderToken, SESSION_ID, null);
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		return orderToken;
	}

	private void stubRefundable() {
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, "{\"paymentSessionId\":\"" + SESSION_ID + "\",\"status\":\"refundable\","
						+ "\"originalAmount\":99000,\"refundableAmount\":99000,"
						+ "\"canCreateRefund\":true,\"refunds\":[]}")));
	}

	private void stubRefundOk() {
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_ID))
				.willReturn(json(200, "{\"id\":\"ref-" + SESSION_ID + "\",\"status\":\"completed\",\"amount\":1000}")));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	// ---------------------------------------------------------------- 조회 헬퍼

	private static SessionUser payer() {
		return new SessionUser("kakao-opt-payer", "옵션구매자");
	}

	private static SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private static OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}

	private int formHeld() {
		return jdbcTemplate.queryForObject("SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}

	private int formSold() {
		return jdbcTemplate.queryForObject("SELECT sold FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}

	private int optionHeld(Long optionId) {
		return jdbcTemplate.queryForObject("SELECT held FROM product_option WHERE id = ?", Integer.class, optionId);
	}

	private int optionSold(Long optionId) {
		return jdbcTemplate.queryForObject("SELECT sold FROM product_option WHERE id = ?", Integer.class, optionId);
	}
}
