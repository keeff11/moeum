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
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.order.dto.BuyerOrderStatus;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.order.dto.ShipmentRequest;
import store.moeum.moeum.order.dto.ShipmentResponse;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 송장 등록 (D-047).
 *
 * <b>이게 없어서 비어 있던 자리가 셋이다</b> — 셀러의 발송 완료 탭, 알림톡 3번의 적재 지점,
 * 구매자가 볼 송장번호. 셋이 다 채워지는지 본다.
 *
 * 경계는 <b>받을 돈이 남았는가</b> 하나다. 그게 틀리면 잔금을 못 받은 채 물건이 나간다.
 */
class ShipmentTest extends IntegrationTest {

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
	private ShipmentService shipmentService;

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

	private OrderFixture.Setup setup;
	private String sellerKakaoId;
	private String orderNo;
	private int sessionSeq;
	private String sessionId;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		sessionSeq = 0;
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		// 단독 판매로 본다 — 잔금이 없어 입고되면 바로 발송 단계다 (D-046)
		setup = fixture.soloSaleForm(10);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 등록

	@Test
	@DisplayName("송장을_등록하면_발송_완료로_넘어간다")
	void 발송_처리() {
		payAndArrive();

		ShipmentResponse response = register("CJ대한통운", "123456789012");

		assertThat(response.newlyShipped()).isTrue();
		assertThat(response.carrier()).isEqualTo("CJ대한통운");
		assertThat(response.shippedAt()).isNotNull();
		assertThat(groupStatus()).containsExactly("SHIPPED");
		assertThat(orderStatus()).containsExactly("SHIPPED");
	}

	@Test
	@DisplayName("셀러의_발송_완료_탭에_뜬다")
	void 발송_완료_탭() {
		// 이 탭은 SHIPPED 로 올리는 코드가 없어서 항상 0건이었다
		payAndArrive();
		register("CJ대한통운", "123456789012");

		SellerOrderPageResponse page = sellerPage(SellerOrderTab.SHIPPED);

		assertThat(page.items()).hasSize(1);
		assertThat(page.counts().shipped()).isEqualTo(1);
		assertThat(sellerPage(SellerOrderTab.PREPARING).items()).isEmpty();
	}

	@Test
	@DisplayName("구매자가_송장번호를_볼_수_있다")
	void 구매자_송장번호() {
		payAndArrive();
		register("CJ대한통운", "123456789012");

		BuyerOrderPageResponse.BuyerOrderItem card = buyerCard();

		assertThat(card.status()).isEqualTo(BuyerOrderStatus.SHIPPED);
		assertThat(card.carrier()).isEqualTo("CJ대한통운");
		assertThat(card.trackingNo()).isEqualTo("123456789012");
	}

	@Test
	@DisplayName("송장_등록_전에는_구매자_카드에_송장이_없다")
	void 등록_전() {
		payAndArrive();

		assertThat(buyerCard().carrier()).isNull();
		assertThat(buyerCard().trackingNo()).isNull();
	}

	@Test
	@DisplayName("발송_완료_알림이_적재된다")
	void 알림_적재() {
		// 알림톡 3번이 이 적재 지점을 기다리고 있었다
		payAndArrive();
		register("CJ대한통운", "123456789012");

		assertThat(outboxTypes()).contains("SHIPPED");
	}

	// ---------------------------------------------------------------- 경계

	@Test
	@DisplayName("입고_전에는_등록할_수_없다")
	void 입고_전() {
		pay();

		assertThatThrownBy(() -> register("CJ대한통운", "123456789012"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.NOT_READY_TO_SHIP);
	}

	@Test
	@DisplayName("잔금을_못_받았으면_등록할_수_없다")
	void 잔금_미납() {
		// 여기서 막지 않으면 받을 돈이 남은 채로 물건이 나간다
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(10, null);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
		payAndArrive();

		assertThatThrownBy(() -> register("CJ대한통운", "123456789012"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.NOT_READY_TO_SHIP);
	}

	@Test
	@DisplayName("남의_주문은_404_다")
	void 남의_주문() {
		payAndArrive();

		// 403 이면 "그 번호가 있긴 하다" 가 새어 나가 주문번호를 훑을 수 있다
		assertThatThrownBy(() -> shipmentService.register(
				"kakao-stranger", orderNo, new ShipmentRequest("CJ대한통운", "04", "1")))
				.isInstanceOf(BusinessException.class);
	}

	// ---------------------------------------------------------------- 수정

	@Test
	@DisplayName("송장번호를_고칠_수_있고_알림은_다시_나가지_않는다")
	void 송장_수정() {
		payAndArrive();
		register("CJ대한통운", "111111111111");

		ShipmentResponse fixed = register("한진택배", "222222222222");

		assertThat(fixed.newlyShipped()).isFalse();
		assertThat(fixed.carrier()).isEqualTo("한진택배");
		assertThat(fixed.trackingNo()).isEqualTo("222222222222");
		// 잘못 적은 번호를 고쳤다고 "발송했습니다" 가 또 가면 구매자는 두 번 보낸 줄 안다
		assertThat(outboxTypes()).filteredOn("SHIPPED"::equals).hasSize(1);
	}

	@Test
	@DisplayName("송장번호를_고쳐도_발송일시는_처음_그대로다")
	void 발송일시는_굳는다() {
		// 발송 기준으로 세는 것들(취소 가능 여부 · 정산)이 같이 흔들리면 안 된다
		payAndArrive();
		var first = register("CJ대한통운", "111111111111");

		var second = register("한진택배", "222222222222");

		// DB 가 DATETIME(6) 이라 마이크로초로 반올림된다. 밀리초로 보면 충분하다
		assertThat(second.shippedAt()).isCloseTo(first.shippedAt(), within(1, ChronoUnit.MILLIS));
	}

	// ---------------------------------------------------------------- 도우미

	private ShipmentResponse register(String carrier, String trackingNo) {
		return shipmentService.register(sellerKakaoId, orderNo,
				new ShipmentRequest(carrier, "04", trackingNo));
	}

	private void payAndArrive() {
		pay();
		jdbcTemplate.update("UPDATE orders SET status = 'ARRIVED'");
	}

	private void pay() {
		String sessionToken = orderService.place(buyer(), order()).sessionToken();
		nextSession();
		String orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + sessionId))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(sessionId))));
		paymentService.confirm(buyer(), orderToken, sessionId, null);

		orderNo = jdbcTemplate.queryForObject("SELECT order_no FROM order_group", String.class);
	}

	private void nextSession() {
		sessionId = "pymt_sess-019f0000-0000-7000-9000-%012d".formatted(++sessionSeq);
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":35000,
						 "supplyAmount":31819,"vat":3181,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(sessionId))));
	}

	private SellerOrderPageResponse sellerPage(SellerOrderTab tab) {
		return sellerOrderService.list(sellerKakaoId, tab, null, null, 0, 20);
	}

	private BuyerOrderPageResponse.BuyerOrderItem buyerCard() {
		return buyerOrderService.list(buyer().kakaoId(), null, 0, 20).items().get(0);
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private OrderCreateRequest order() {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 1)));
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}

	private List<String> orderStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM orders", String.class);
	}

	private List<String> outboxTypes() {
		return jdbcTemplate.queryForList("SELECT event_type FROM outbox", String.class);
	}
}
