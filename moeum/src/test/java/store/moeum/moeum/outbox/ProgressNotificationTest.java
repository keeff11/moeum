package store.moeum.moeum.outbox;

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
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.SecondPaymentReminderBatch;
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
 * 진행 · 모집 결과 · 미납 독촉 알림 적재 (D-050).
 *
 * <b>승인된 템플릿이 없어 실제로 나가지는 않는다.</b> 릴레이가 로그만 남기고 SENT 로
 * 넘긴다 (D-040). 그래서 여기서 보는 것은 <b>outbox 에 적재되는가</b>이고,
 * 템플릿 id 를 설정에 채우는 순간 그대로 나가기 시작한다.
 *
 * 경계는 둘이다 — <b>같은 사실을 두 번 적재하지 않는가</b>,
 * 그리고 <b>알림이 터졌을 때 본 작업을 되돌리지 않는가</b>.
 */
class ProgressNotificationTest extends IntegrationTest {

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
	private SecondPaymentReminderBatch reminderBatch;

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

	// ---------------------------------------------------------------- 항목 4 진행 상태

	@Test
	@DisplayName("수동_마감이_진행_단계_알림을_적재한다")
	void 마감_알림() {
		pay();

		saleFormService.close(sellerKakaoId, setup.saleFormId());

		assertThat(types()).contains("PROGRESS_CHANGED");
		assertThat(stageOf()).isEqualTo("CLOSED");
	}

	@Test
	@DisplayName("마감_배치도_적재한다")
	void 배치_마감_알림() {
		pay();
		jdbcTemplate.update("UPDATE sale_form SET closes_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");

		closeBatch.closeOnce();

		assertThat(types()).contains("PROGRESS_CHANGED");
	}

	@Test
	@DisplayName("발주가_제작중_알림을_적재한다")
	void 발주_알림() {
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());
		jdbcTemplate.update("DELETE FROM outbox");

		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		assertThat(types()).containsExactly("PROGRESS_CHANGED");
		assertThat(stageOf()).isEqualTo("PRODUCING");
	}

	@Test
	@DisplayName("바뀐_것이_없으면_적재하지_않는다")
	void 중복_적재_없음() {
		// 셀러가 같은 버튼을 다시 눌러도 구매자가 같은 알림을 또 받으면 안 된다
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());
		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());
		jdbcTemplate.update("DELETE FROM outbox");

		saleFormService.startProducing(sellerKakaoId, setup.saleFormId());

		assertThat(types()).isEmpty();
	}

	@Test
	@DisplayName("입고는_진행_알림을_적재하지_않는다")
	void 입고는_제외() {
		// 그 시점엔 2차금 청구 알림이 나가고 문구가 "입고됐으니 잔금을 내라" 다
		pay();
		saleFormService.close(sellerKakaoId, setup.saleFormId());
		jdbcTemplate.update("DELETE FROM outbox");

		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());

		assertThat(types()).containsExactly("SECOND_PAYMENT_DUE");
	}

	// ---------------------------------------------------------------- 항목 8 미납 독촉

	@Test
	@DisplayName("입고된_미납_묶음에_독촉을_적재한다")
	void 독촉_적재() {
		pay();
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		jdbcTemplate.update("DELETE FROM outbox");

		assertThat(reminderBatch.remindOnce()).isEqualTo(1);
		assertThat(types()).containsExactly("SECOND_PAYMENT_OVERDUE");
	}

	@Test
	@DisplayName("쿨다운_안에_있으면_독촉하지_않는다")
	void 독촉_쿨다운() {
		// 셀러의 수동 청구와 같은 이력을 본다. 표를 나누면 구매자가 하루에 두 번 받는다
		pay();
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());
		reminderBatch.remindOnce();
		jdbcTemplate.update("DELETE FROM outbox");

		assertThat(reminderBatch.remindOnce()).isZero();
		assertThat(types()).isEmpty();
	}

	@Test
	@DisplayName("입고_전에는_독촉하지_않는다")
	void 입고_전_독촉_없음() {
		// 아직 낼 수 없는 잔금을 독촉하면 구매자는 낼 방법이 없다
		pay();

		assertThat(reminderBatch.remindOnce()).isZero();
	}

	@Test
	@DisplayName("단독_판매는_잔금이_없어_독촉_대상이_아니다")
	void 단독은_제외() {
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.soloSaleForm(10);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
		pay();
		saleFormService.markArrived(sellerKakaoId, setup.saleFormId());

		assertThat(reminderBatch.remindOnce()).isZero();
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
		jdbcTemplate.update("DELETE FROM outbox");
	}

	private void nextSession() {
		sessionId = "pymt_sess-019f0000-0000-7000-9000-%012d".formatted(++sessionSeq);
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json("""
						{"id":"%s","status":"created","amount":20000,
						 "supplyAmount":18182,"vat":1818,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(sessionId))));
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

	private List<String> types() {
		return jdbcTemplate.queryForList("SELECT event_type FROM outbox ORDER BY id", String.class);
	}

	/** payload 에 실린 stage. 단계마다 타입을 나누지 않았으므로 이 값으로 구분한다 */
	private String stageOf() {
		return jdbcTemplate.queryForObject(
				"SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.stage')) FROM outbox"
						+ " WHERE event_type = 'PROGRESS_CHANGED' ORDER BY id LIMIT 1", String.class);
	}
}
