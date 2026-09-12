package store.moeum.moeum.order;

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
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.order.dto.BuyerOrderStatus;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 단독 판매의 배송비 (D-046).
 *
 * <b>단독 판매는 2차금 단계를 건너뛴다</b> (domain.md 1절). 그런데 배송비는 2차금으로
 * 이연되게 돼 있어서, 이연하면 청구할 자리가 사라진다 — 구매자는 상품값만 내고
 * 셀러가 배송비를 떠안는다.
 *
 * 그래서 2차금이 없는 묶음은 1차금에서 배송비까지 받는다. 여기서 보는 것은
 * <b>어느 쪽으로도 이중 청구가 되지 않는가</b> 다.
 */
class SoloShippingFeeTest extends IntegrationTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-0000000000e1";
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
	private SellerOrderService sellerOrderService;

	@Autowired
	private BuyerOrderService buyerOrderService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	@Test
	@DisplayName("단독_판매는_1차금에_배송비가_들어간다")
	void 단독은_1차금에_배송비() {
		OrderFixture.Setup solo = fixture.soloSaleForm(10);

		OrderGroupResponse group = orderService.place(buyer(), order(solo.optionId(), 1));

		// 상품 32,000 + 배송비 3,000
		assertThat(group.deposit1Total()).isEqualTo(32_000);
		assertThat(group.deposit2Total()).isZero();
		assertThat(group.shippingFee()).isEqualTo(3_000);
		assertThat(group.firstPaymentAmount()).isEqualTo(35_000);
	}

	@Test
	@DisplayName("결제창에_뜨는_금액에도_배송비가_들어간다")
	void 결제창_금액() {
		// 신고된 증상이 이것이다 — 결제창이 배송비 빠진 금액으로 열렸다
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		String sessionToken = orderService.place(buyer(), order(solo.optionId(), 1)).sessionToken();
		stubCreateSession();

		paymentService.pay(buyer(), sessionToken);

		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/payment/v3/session"))
				.withRequestBody(matchingJsonPath("$.amount", com.github.tomakehurst.wiremock.client.WireMock.equalTo("35000"))));
		assertThat(paymentAmount()).containsExactly(35_000);
	}

	@Test
	@DisplayName("공동구매는_그대로_2차금에서_받는다")
	void 공동구매는_그대로() {
		// 배송비를 1차금에도 더하면 같은 배송비를 두 번 청구하게 된다
		OrderFixture.Setup group = fixture.saleForm(10, null);

		OrderGroupResponse response = orderService.place(buyer(), order(group.optionId(), 1));

		assertThat(response.deposit1Total()).isEqualTo(20_000);
		assertThat(response.firstPaymentAmount()).isEqualTo(20_000);
		assertThat(response.shippingFee()).isEqualTo(3_000);
	}

	@Test
	@DisplayName("단독_판매는_2차금_청구_대상이_아니다")
	void 단독은_2차금_대상이_아니다() {
		// 배송비를 1차금에서 이미 받았다. 남겨 두면 "2차금 미납" 탭에 영원히 쌓인다
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		payAndArrive(solo);

		String sellerKakaoId = sellerRepository.findById(solo.sellerId()).orElseThrow().getKakaoId();
		SellerOrderPageResponse page =
				sellerOrderService.list(sellerKakaoId, SellerOrderTab.SECOND_UNPAID, null, null, 0, 20);

		assertThat(page.items()).isEmpty();
		assertThat(page.counts().secondUnpaid()).isZero();
	}

	@Test
	@DisplayName("배송비를_두_번_청구하지_않는다")
	void 이중_청구가_없다() {
		// 카드의 amount 는 1차금 + 2차금이다. 2차금에서 배송비를 또 더하면 38,000 이 된다
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		payAndArrive(solo);

		assertThat(totalAmountOf(solo)).isEqualTo(35_000);
	}

	// ---------------------------------------------------------------- 입고 후 발송 단계

	@Test
	@DisplayName("입고되면_셀러의_배송_준비_중_탭에_뜬다")
	void 셀러_배송_준비_중() {
		// 묶음 상태가 SECOND_PAID 로 안 넘어가서, 그것만 보면 보낼 주문을 못 찾는다
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		payAndArrive(solo);

		SellerOrderPageResponse page = sellerPage(solo, SellerOrderTab.PREPARING);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).statusLabel()).isEqualTo("배송 준비 중");
		assertThat(page.counts().preparing()).isEqualTo(1);
	}

	@Test
	@DisplayName("입고되면_구매자에게_배송_준비_중으로_보인다")
	void 구매자_배송_준비_중() {
		// 단독 판매에는 '제작 중' 이라는 단계가 없다 (domain.md 1절)
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		payAndArrive(solo);

		BuyerOrderPageResponse page = buyerOrderService.list(buyer().kakaoId(), null, 0, 20);

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).status()).isEqualTo(BuyerOrderStatus.PREPARING);
	}

	@Test
	@DisplayName("입고_전이면_아직_배송_준비_중이_아니다")
	void 입고_전() {
		OrderFixture.Setup solo = fixture.soloSaleForm(10);
		pay(solo);

		assertThat(sellerPage(solo, SellerOrderTab.PREPARING).items()).isEmpty();
		assertThat(buyerOrderService.list(buyer().kakaoId(), null, 0, 20).items().get(0).status())
				.isNotEqualTo(BuyerOrderStatus.PREPARING);
	}

	@Test
	@DisplayName("공동구매는_입고만으로_배송_준비_중이_되지_않는다")
	void 공동구매는_잔금까지() {
		// 잔금을 받아야 발송 단계다. 여기까지 넓히면 미납인 묶음이 발송 대기로 보인다
		OrderFixture.Setup group = fixture.saleForm(10, null);
		payAndArrive(group);

		assertThat(sellerPage(group, SellerOrderTab.PREPARING).items()).isEmpty();
		assertThat(sellerPage(group, SellerOrderTab.SECOND_UNPAID).items()).hasSize(1);
	}

	// ---------------------------------------------------------------- 도우미

	private SellerOrderPageResponse sellerPage(OrderFixture.Setup setup, SellerOrderTab tab) {
		String sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
		return sellerOrderService.list(sellerKakaoId, tab, null, null, 0, 20);
	}

	/** 결제까지 마치고 입고 처리한다 — 2차금과 발송 단계 판정이 서는 지점이다 */
	private void payAndArrive(OrderFixture.Setup setup) {
		pay(setup);
		jdbcTemplate.update("UPDATE orders SET status = 'ARRIVED'");
	}

	private void pay(OrderFixture.Setup setup) {
		String sessionToken = orderService.place(buyer(), order(setup.optionId(), 1)).sessionToken();
		stubCreateSession();
		String orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))));
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
	}

	/** 카드의 amount 는 1차금 + 2차금이다. 이중 청구가 있으면 여기서 드러난다 */
	private int totalAmountOf(OrderFixture.Setup setup) {
		String sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
		SellerOrderPageResponse page =
				sellerOrderService.list(sellerKakaoId, SellerOrderTab.ALL, null, null, 0, 20);
		return page.items().get(0).amount();
	}

	private void stubCreateSession() {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":35000,
						 "supplyAmount":31819,"vat":3181,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
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

	private List<Integer> paymentAmount() {
		return jdbcTemplate.queryForList("SELECT amount FROM payment", Integer.class);
	}
}
