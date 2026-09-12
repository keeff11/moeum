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
import store.moeum.moeum.order.dto.BuyerOrderStatus;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.saleform.SaleFormCloseBatch;
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

/**
 * 주문의 진행 단계 (D-049).
 *
 * <b>이게 없어서 구매자가 진행 상황을 못 봤다.</b> {@code orders.status} 를 RECRUITING ·
 * CLOSED · PRODUCING 으로 올리는 코드가 한 줄도 없어서, 공동구매 주문이 PAID 에서
 * 입고로 바로 뛰었다 — 구매자 화면(B8 · B13)은 입고될 때까지 계속 "결제 완료" 였다.
 *
 * <b>가장 위험한 것은 입고 처리다.</b> 입고 대상 쿼리가 PAID 만 집고 있었으므로,
 * 결제 직후 RECRUITING 으로 올리면 입고가 0건이 되고 <b>2차금이 영영 안 열린다.</b>
 * 그 경로를 여기서 못 박는다.
 */
class OrderProgressTest extends IntegrationTest {

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
	private SaleFormCloseBatch closeBatch;

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
	private int sessionSeq;
	private String sessionId;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		sessionSeq = 0;
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(10, null);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 흐름

	@Test
	@DisplayName("공동구매는_결제하면_바로_모집_중이_된다")
	void 결제_후_모집중() {
		pay();

		assertThat(orderStatus()).containsExactly("RECRUITING");
		assertThat(buyerBadge()).isEqualTo(BuyerOrderStatus.RECRUITING);
	}

	@Test
	@DisplayName("단독_판매는_모집이_없어서_결제_완료에_머문다")
	void 단독은_그대로() {
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.soloSaleForm(10);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();

		pay();

		assertThat(orderStatus()).containsExactly("PAID");
	}

	@Test
	@DisplayName("폼을_마감하면_주문도_모집_마감으로_넘어간다")
	void 수동_마감() {
		// 폼만 마감하면 구매자 화면이 마감된 공구를 계속 "모집 중" 으로 보여 준다
		pay();

		saleFormService.close(sellerKakaoId, setup.saleFormId());

		assertThat(orderStatus()).containsExactly("CLOSED");
		assertThat(buyerBadge()).isEqualTo(BuyerOrderStatus.CLOSED);
	}

	@Test
	@DisplayName("마감_배치도_주문을_같이_넘긴다")
	void 배치_마감() {
		pay();
		jdbcTemplate.update("UPDATE sale_form SET closes_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");

		closeBatch.closeOnce();

		assertThat(orderStatus()).containsExactly("CLOSED");
	}

	@Test
	@DisplayName("발주하면_제작_중이_된다")
	void 발주() {
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());

		int producing = saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		assertThat(producing).isEqualTo(1);
		assertThat(orderStatus()).containsExactly("PRODUCING");
		assertThat(buyerBadge()).isEqualTo(BuyerOrderStatus.PRODUCING);
	}

	@Test
	@DisplayName("모집_중에는_발주할_수_없다")
	void 마감_전_발주() {
		// 모집이 끝나야 몇 개를 만들지가 정해진다. 그 전에 발주하면 발주서 수량과 어긋난다
		pay();

		assertThat(saleFormService.startProducing(sellerKakaoId, setup.saleFormId())).isZero();
		assertThat(orderStatus()).containsExactly("RECRUITING");
	}

	@Test
	@DisplayName("발주를_다시_불러도_두_번_세지_않는다")
	void 발주_멱등() {
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());
		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		assertThat(saleFormService.startProducing(sellerKakaoId, setup.saleFormId())).isZero();
	}

	// ---------------------------------------------------------------- 입고 (돈이 걸린 경로)

	@Test
	@DisplayName("모집_중인_주문도_입고_처리된다")
	void 입고_모집중에서() {
		// PAID 만 집으면 여기서 0건이 되고 2차금이 영영 안 열린다
        pay();

		assertThat(saleFormService.markArrived(sellerKakaoId, setup.saleFormId())).isEqualTo(1);
		assertThat(orderStatus()).containsExactly("ARRIVED");
	}

	@Test
	@DisplayName("제작_중인_주문도_입고_처리된다")
	void 입고_제작중에서() {
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());
		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		assertThat(saleFormService.markArrived(sellerKakaoId, setup.saleFormId())).isEqualTo(1);
		assertThat(orderStatus()).containsExactly("ARRIVED");
	}

	@Test
	@DisplayName("단계를_건너뛰고_바로_입고해도_된다")
	void 입고_건너뛰기() {
		// 그렇게 일하는 셀러가 있고, 막아서 얻는 것이 없다
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());

		assertThat(saleFormService.markArrived(sellerKakaoId, setup.saleFormId())).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 도우미

	private void pay() {
		String sessionToken = orderService.place(buyer(), order()).sessionToken();
		nextSession();
		String orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + sessionId))
				.willReturn(json("""
						{"id":"%s","status":"captured"}""".formatted(sessionId))));
		paymentService.confirm(buyer(), orderToken, sessionId, null);
	}

	private void nextSession() {
		sessionId = "pymt_sess-019f0000-0000-7000-9000-%012d".formatted(++sessionSeq);
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json("""
						{"id":"%s","status":"created","amount":20000,
						 "supplyAmount":18182,"vat":1818,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(sessionId))));
	}

	private BuyerOrderStatus buyerBadge() {
		return buyerOrderService.list("kakao-payer", null, 0, 20).items().get(0).status();
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private OrderCreateRequest order() {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 1)));
	}

	private List<String> orderStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM orders", String.class);
	}
}
