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
import store.moeum.moeum.order.dto.BuyerOrderStatus;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.saleform.SaleFormCloseBatch;
import store.moeum.moeum.saleform.SaleFormService;
import store.moeum.moeum.saleform.dto.SaleFormProgressResponse;
import store.moeum.moeum.saleform.dto.SaleFormProgressResponse.StageStep;
import store.moeum.moeum.saleform.dto.SaleStage;
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

	// ---------------------------------------------------------------- 진행 현황 (S9 · D-058)

	@Test
	@DisplayName("주문이_없어도_타임라인은_모집_중에_서_있다")
	void 현황_주문_없음() {
		// 아직 아무도 안 샀어도 판매는 모집 중이다. 여기가 비면 셀러는 단계를 볼 곳이 없다
		SaleFormProgressResponse progress = progress();

		assertThat(progress.stage()).isEqualTo(SaleStage.RECRUITING);
		assertThat(progress.totalOrders()).isZero();
		// 화면의 단계 스트립 여섯 칸 그대로다
		assertThat(progress.stages()).extracting(StageStep::stage)
				.containsExactly(SaleStage.RECRUITING, SaleStage.CLOSED, SaleStage.ORDERED,
						SaleStage.PRODUCING, SaleStage.ARRIVED, SaleStage.SHIPPED);
		assertThat(progress.stages()).extracting(StageStep::label)
				.containsExactly("모집중", "마감", "발주", "제작중", "입고·2차금", "발송");
	}

	@Test
	@DisplayName("발주를_누르면_현황이_제작_중으로_넘어간다")
	void 현황_발주() {
		// 셀러가 버튼을 누른 뒤 무엇이 달라졌는지 볼 곳이 이 API 다
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());

		assertThat(progress().stage()).isEqualTo(SaleStage.CLOSED);
		assertThat(progress().producibleOrders()).isEqualTo(1);
		assertThat(stepOf(progress(), SaleStage.ORDERED).reached()).isFalse();
		assertThat(stepOf(progress(), SaleStage.ORDERED).orders()).isZero();

		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		SaleFormProgressResponse after = progress();
		assertThat(after.stage()).isEqualTo(SaleStage.PRODUCING);
		assertThat(after.producibleOrders()).isZero();
		assertThat(after.arrivableOrders()).isEqualTo(1);
		assertThat(stepOf(after, SaleStage.PRODUCING).orders()).isEqualTo(1);
		assertThat(stepOf(after, SaleStage.PRODUCING).current()).isTrue();
		assertThat(stepOf(after, SaleStage.CLOSED).reached()).isTrue();
		assertThat(stepOf(after, SaleStage.ARRIVED).reached()).isFalse();
		// 발주와 제작중은 같은 전이의 두 칸이다 — 지나온 칸에 0건이 찍히면
		// 셀러는 발주가 빠진 줄로 읽는다
		assertThat(stepOf(after, SaleStage.ORDERED).reached()).isTrue();
		assertThat(stepOf(after, SaleStage.ORDERED).current()).isFalse();
		assertThat(stepOf(after, SaleStage.ORDERED).orders()).isEqualTo(1);
	}

	@Test
	@DisplayName("입고를_누르면_현황이_입고로_넘어가고_입고_대상이_없어진다")
	void 현황_입고() {
		pay();

		SaleFormProgressResponse after = arrive();

		assertThat(after.stage()).isEqualTo(SaleStage.ARRIVED);
		assertThat(after.arrivableOrders()).isZero();
		assertThat(stepOf(after, SaleStage.ARRIVED).orders()).isEqualTo(1);
	}

	@Test
	@DisplayName("현황은_가장_덜_진행된_주문의_단계를_말한다")
	void 현황_가장_덜_진행된_것() {
		// 가장 앞선 것으로 잡으면 한 건만 발송해도 타임라인이 발송으로 뛴다.
		// 셀러가 봐야 하는 것은 아직 처리하지 않은 쪽이다
		pay();
		pay();
		jdbcTemplate.update("UPDATE orders SET status = 'SHIPPED' "
				+ "WHERE id = (SELECT id FROM (SELECT MIN(id) AS id FROM orders) t)");

		SaleFormProgressResponse progress = progress();

		assertThat(progress.stage()).isEqualTo(SaleStage.RECRUITING);
		assertThat(progress.totalOrders()).isEqualTo(2);
		assertThat(stepOf(progress, SaleStage.SHIPPED).orders()).isEqualTo(1);
		assertThat(stepOf(progress, SaleStage.SHIPPED).reached()).isFalse();
	}

	@Test
	@DisplayName("취소된_주문은_단계에_서지_않고_따로_세어_준다")
	void 현황_취소() {
		// 몇 건이 빠졌는지는 셀러가 알아야 발주 수량과 대조할 수 있다
		pay();
		pay();
		jdbcTemplate.update("UPDATE orders SET status = 'CANCELED' "
				+ "WHERE id = (SELECT id FROM (SELECT MIN(id) AS id FROM orders) t)");

		SaleFormProgressResponse progress = progress();

		assertThat(progress.totalOrders()).isEqualTo(1);
		assertThat(progress.canceledOrders()).isEqualTo(1);
		assertThat(stepOf(progress, SaleStage.RECRUITING).orders()).isEqualTo(1);
	}

	@Test
	@DisplayName("단독_판매의_타임라인에는_모집과_발주가_없다")
	void 현황_단독() {
		// 없는 칸을 회색으로 세워 두면 셀러는 영영 켜지지 않는 단계를 기다린다
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.soloSaleForm(10);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
		pay();

		SaleFormProgressResponse progress = progress();

		assertThat(progress.stages()).extracting(StageStep::stage)
				.containsExactly(SaleStage.PAID, SaleStage.ARRIVED, SaleStage.SHIPPED);
		assertThat(progress.stage()).isEqualTo(SaleStage.PAID);
		// 받을 잔금이 없어 입고가 곧 배송 준비다 (D-046)
		assertThat(stepOf(progress, SaleStage.ARRIVED).label()).isEqualTo("배송 준비 중");
	}

	@Test
	@DisplayName("남의_판매_현황은_볼_수_없다")
	void 현황_남의_폼() {
		assertThatThrownBy(() -> saleFormService.progress("kakao-not-seller", setup.saleFormId()))
				.isInstanceOf(BusinessException.class);
	}

	// ---------------------------------------------------------------- 도우미

	private SaleFormProgressResponse progress() {
		return saleFormService.progress(sellerKakaoId, setup.saleFormId());
	}

	/** 입고 처리 후의 현황. 실제 API 도 처리 결과에 현황을 실어 준다 */
	private SaleFormProgressResponse arrive() {
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		return progress();
	}

	private static StageStep stepOf(SaleFormProgressResponse progress, SaleStage stage) {
		return progress.stages().stream()
				.filter(step -> step.stage() == stage)
				.findFirst()
				.orElseThrow(() -> new AssertionError("타임라인에 없는 단계다: " + stage));
	}


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
