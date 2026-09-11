package store.moeum.moeum.cart;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제가 끝나면 장바구니에서 그 항목을 뺀다.
 *
 * <b>시점이 이 테스트의 핵심이다.</b> 홀드 시점에 비우면 결제창을 닫은 구매자의 장바구니가
 * 사라진다 — 홀드는 만료 배치가 걷어 가지만 장바구니는 되돌려 줄 방법이 없다.
 * 그래서 "결제 전에는 남아 있고, 확정되면 사라진다" 를 양쪽 다 본다.
 */
class CartClearOnPaidTest extends IntegrationTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-0000000000c1";
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
	private CartService cartService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(10, null);
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	@Test
	@DisplayName("결제가_확정되면_주문한_항목이_장바구니에서_빠진다")
	void 결제_확정이면_빠진다() {
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 2));
		assertThat(cartItemCount()).isEqualTo(1);

		payFor(setup.optionId(), 2);

		assertThat(cartService.findMine(buyer())).isEmpty();
		assertThat(cartItemCount()).isZero();
	}

	@Test
	@DisplayName("결제_전에는_장바구니가_그대로다")
	void 홀드만으로는_안_빠진다() {
		// 홀드 시점에 비우면 결제창을 닫은 구매자의 장바구니가 사라진다.
		// 홀드는 15분 뒤 만료 배치가 걷어 가지만 장바구니는 되돌려 줄 방법이 없다
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 2));

		orderService.place(buyer(), order(setup.optionId(), 2));

		assertThat(cartItemCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("결제가_실패하면_장바구니가_남는다")
	void 결제_실패면_남는다() {
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 2));

		String orderToken = startPayment(setup.optionId(), 2);
		stubCapture(400, """
				{"code":"INVALID_REQUEST"}""");

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 다시 결제하려면 장바구니에 그대로 있어야 한다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		assertThat(cartItemCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("주문하지_않은_항목은_장바구니에_남는다")
	void 안_산_것은_남는다() {
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 1));
		cartService.add(buyer(), new CartAddRequest(setup.secondOptionId(), 1));

		payFor(setup.optionId(), 1);

		List<CartResponse> carts = cartService.findMine(buyer());
		assertThat(carts).hasSize(1);
		assertThat(carts.get(0).items())
				.extracting(CartResponse.CartItemResponse::optionId)
				.containsExactly(setup.secondOptionId());
	}

	@Test
	@DisplayName("장바구니가_비면_셀러_묶음도_같이_사라진다")
	void 빈_장바구니는_지운다() {
		// 남겨 두면 항목 없는 셀러 묶음이 화면에 뜬다
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 1));
		assertThat(cartCount()).isEqualTo(1);

		payFor(setup.optionId(), 1);

		assertThat(cartCount()).isZero();
	}

	@Test
	@DisplayName("장바구니가_비어_있어도_결제는_그대로_확정된다")
	void 장바구니가_없어도_결제는_된다() {
		// 상품 페이지에서 바로 산 경우다. 지울 것이 없다
		payFor(setup.optionId(), 2);

		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(cartItemCount()).isZero();
	}

	// ---------------------------------------------------------------- 도우미

	private void payFor(Long optionId, int qty) {
		String orderToken = startPayment(optionId, qty);
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
	}

	private String startPayment(Long optionId, int qty) {
		String sessionToken = orderService.place(buyer(), order(optionId, qty)).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
		return paymentService.pay(buyer(), sessionToken).orderToken();
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
		return new SessionUser("kakao-payer", "결제자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}

	private int cartItemCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart_item", Integer.class);
	}

	private int cartCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cart", Integer.class);
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}
}
