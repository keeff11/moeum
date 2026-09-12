package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.refund.ShortfallCancelBatch;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목표수량 미달 자동 취소 (roadmap 6단계, D-026).
 *
 * <b>이 배치는 이미 받은 돈을 돌려준다.</b> 두 번 돌면 이중 환불이고, 한 건이 터졌다고
 * 멈추면 뒤의 구매자들이 돈을 못 받는다. 확인할 것은 그 두 가지다.
 */
@Import(ShortfallCancelTest.FixedClockConfig.class)
class ShortfallCancelTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 낮 12시로 고정한다. 고정하지 않으면 EOB 시간대에 돌릴 때 취소가 전부 막힌다 */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(LocalDateTime.of(2026, 9, 8, 12, 0).atZone(KST).toInstant(), KST);
		}
	}

	private static final String SESSION_A = "pymt_sess-short-aaaa-0000-00000001";
	private static final String SESSION_B = "pymt_sess-short-bbbb-0000-00000002";

	/** 옵션 A 1차금 20,000 (OrderFixture) */
	private static final int DEPOSIT1 = 20000;

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
	private ShortfallCancelBatch batch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		fixture.buyerWithAddress("kakao-shortfall-a", "미달가");
		fixture.buyerWithAddress("kakao-shortfall-b", "미달나");
		setup = fixture.saleForm(100, null);
	}

	// ---------------------------------------------------------------- CANCEL

	@Test
	@DisplayName("목표에_못_미치면_그_폼의_주문을_전부_취소한다")
	void 미달_취소() {
		payFirst(buyerA(), SESSION_A, 2);
		payFirst(buyerB(), SESSION_B, 3);
		closeWith(10, "CANCEL");
		stubRefundOk();

		assertThat(batch.handleOnce()).isEqualTo(1);

		// 구매자 둘 다 1차금을 돌려받는다. 2차금은 아직 결제 전이라 대상이 아니다
		assertThat(refundAmounts()).containsExactlyInAnyOrder(DEPOSIT1 * 2, DEPOSIT1 * 3);
		assertThat(orderStatuses()).containsOnly("CANCELED");
		assertThat(groupStatuses()).containsOnly("CANCELED");
	}

	@Test
	@DisplayName("마감_후_취소라_재고는_되돌리지_않는다")
	void 재고는_그대로() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, "CANCEL");
		stubRefundOk();

		batch.handleOnce();

		// D-024. 마감된 폼은 그 자리를 다시 팔 수 없어 되돌려도 의미가 없다
		assertThat(sold()).isEqualTo(2);
	}

	@Test
	@DisplayName("한_주문이_거절돼도_나머지는_취소한다")
	void 한_건이_실패해도_계속() {
		payFirst(buyerA(), SESSION_A, 2);
		payFirst(buyerB(), SESSION_B, 3);
		closeWith(10, "CANCEL");
		stubInspect(SESSION_A);
		stubInspect(SESSION_B);
		// A 는 확정 거절, B 는 성공
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_A))
				.willReturn(json(409, "{\"status\":409,\"result\":{\"code\":\"REFUND_NOT_IN_REFUNDABLE_STATE\"}}")));
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_B))
				.willReturn(json(200, "{\"id\":\"ref-b\",\"status\":\"completed\",\"amount\":60000}")));

		batch.handleOnce();

		// 앞에서 멈추면 뒤의 구매자는 다음 주기를 기다리는데, 그 주기는 오지 않는다
		assertThat(refundStatuses()).containsExactlyInAnyOrder("FAILED", "COMPLETED");
		// 공동구매는 결제 확정 시 RECRUITING 이 된다 (D-049). 남은 주문은 그대로다
		assertThat(orderStatuses()).containsExactlyInAnyOrder("RECRUITING", "CANCELED");
	}

	// ---------------------------------------------------------------- 멱등

	@Test
	@DisplayName("두_번_돌아도_취소는_한_번만_나간다")
	void 멱등() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, "CANCEL");
		stubRefundOk();

		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(batch.handleOnce()).isZero();

		// 이미 나간 돈을 다시 돌려주면 이중 환불이다
		assertThat(refundAmounts()).hasSize(1);
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION_A)));
	}

	@Test
	@DisplayName("미확정으로_끝나도_done_을_찍어_다시_보내지_않는다")
	void 미확정도_done() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, "CANCEL");
		stubInspect(SESSION_A);
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION_A))
				.willReturn(json(409, "{\"status\":409,\"result\":{\"code\":\"REFUND_TEMPORARY_UNAVAILABLE\"}}")));

		batch.handleOnce();

		assertThat(refundStatuses()).containsExactly("PROCESSING");
		// 결과를 모르는 건을 다시 보내면 두 번 환불된다. 취소 대사 배치가 끝낸다
		assertThat(batch.handleOnce()).isZero();
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION_A)));
	}

	// ---------------------------------------------------------------- 다른 정책

	@Test
	@DisplayName("목표를_채웠으면_아무것도_하지_않는다")
	void 목표_달성() {
		payFirst(buyerA(), SESSION_A, 5);
		closeWith(5, "CANCEL");
		stubRefundOk();

		// 훑기는 하되(1건) 취소는 나가지 않는다
		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(refundAmounts()).isEmpty();
		// 공동구매는 결제 확정 시 RECRUITING 이다 (D-049) — 취소가 안 나갔다는 뜻은 그대로다
		assertThat(orderStatuses()).containsOnly("RECRUITING");
	}

	@Test
	@DisplayName("PROCEED_면_미달이어도_취소하지_않는다")
	void 진행() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, "PROCEED");
		stubRefundOk();

		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(refundAmounts()).isEmpty();
	}

	@Test
	@DisplayName("EXTEND_는_연장_규칙이_없어_취소하지_않고_넘긴다")
	void 연장은_보류() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, "EXTEND");
		stubRefundOk();

		// 몇 번까지 · 얼마나 미룰지가 기획 미확정이다. 임의로 정해 돈을 돌려주지 않는다
		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(refundAmounts()).isEmpty();
		// 공동구매는 결제 확정 시 RECRUITING 이다 (D-049) — 취소가 안 나갔다는 뜻은 그대로다
		assertThat(orderStatuses()).containsOnly("RECRUITING");
	}

	@Test
	@DisplayName("정책이_비어_있으면_취소하지_않는다")
	void 정책_미설정() {
		payFirst(buyerA(), SESSION_A, 2);
		closeWith(10, null);
		stubRefundOk();

		// 정책이 없다고 남의 돈을 돌려주지 않는다
		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(refundAmounts()).isEmpty();
	}

	@Test
	@DisplayName("단독판매는_미달이라는_개념이_없다")
	void 단독판매() {
		payFirst(buyerA(), SESSION_A, 2);
		jdbcTemplate.update("UPDATE sale_form SET sale_type = 'SOLO', target_qty = 10, "
				+ "shortfall_policy = 'CANCEL', status = 'CLOSED' WHERE id = ?", setup.saleFormId());
		stubRefundOk();

		assertThat(batch.handleOnce()).isEqualTo(1);
		assertThat(refundAmounts()).isEmpty();
	}

	// ---------------------------------------------------------------- 대상 선별

	@Test
	@DisplayName("아직_마감되지_않은_폼은_집지_않는다")
	void 판매_중() {
		payFirst(buyerA(), SESSION_A, 2);
		jdbcTemplate.update("UPDATE sale_form SET target_qty = 10, shortfall_policy = 'CANCEL' WHERE id = ?",
				setup.saleFormId());
		stubRefundOk();

		// SELLING 중에는 아직 미달인지 판단할 수 없다
		assertThat(batch.handleOnce()).isZero();
		assertThat(refundAmounts()).isEmpty();
	}

	@Test
	@DisplayName("결제되지_않은_주문은_취소_대상이_아니다")
	void 미결제_주문() {
		orderService.place(buyerA(), order(2));   // 홀드만 잡고 결제하지 않는다
		payFirst(buyerB(), SESSION_B, 3);
		closeWith(10, "CANCEL");
		stubRefundOk();

		batch.handleOnce();

		// 돌려줄 돈이 없다. 홀드는 만료 배치가 푼다
		assertThat(refundAmounts()).containsExactly(DEPOSIT1 * 3);
		assertThat(orderStatuses()).containsExactlyInAnyOrder("CREATED", "CANCELED");
	}

	// ---------------------------------------------------------------- 도우미

	private void payFirst(SessionUser buyer, String session, int qty) {
		String sessionToken = orderService.place(buyer, order(qty)).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, "{\"id\":\"" + session + "\",\"status\":\"created\",\"amount\":"
						+ (DEPOSIT1 * qty) + ",\"supplyAmount\":" + (DEPOSIT1 * qty)
						+ ",\"vat\":0,\"taxFreeAmount\":0,\"currency\":\"KRW\"}")));
		String orderToken = paymentService.pay(buyer, sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + session))
				.willReturn(json(200, "{\"id\":\"" + session + "\",\"status\":\"captured\"}")));
		paymentService.confirm(buyer, orderToken, session, null);
		POINT3.resetAll();
	}

	/** 목표수량과 정책을 정한 뒤 마감한다. 마감 배치가 하는 일을 한 줄로 대신한다 */
	private void closeWith(int targetQty, String policy) {
		jdbcTemplate.update("UPDATE sale_form SET target_qty = ?, shortfall_policy = ?, status = 'CLOSED' "
				+ "WHERE id = ?", targetQty, policy, setup.saleFormId());
	}

	private void stubRefundOk() {
		for (String session : List.of(SESSION_A, SESSION_B)) {
			stubInspect(session);
			POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + session))
					.willReturn(json(200, "{\"id\":\"ref-" + session
							+ "\",\"status\":\"completed\",\"amount\":1000}")));
		}
	}

	private void stubInspect(String session) {
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + session))
				.willReturn(json(200, "{\"paymentSessionId\":\"" + session + "\",\"status\":\"refundable\","
						+ "\"originalAmount\":999000,\"refundableAmount\":999000,"
						+ "\"canCreateRefund\":true,\"refunds\":[]}")));
	}

	private OrderCreateRequest order(int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyerA() {
		return new SessionUser("kakao-shortfall-a", "구매자 가");
	}

	private static SessionUser buyerB() {
		return new SessionUser("kakao-shortfall-b", "구매자 나");
	}

	private List<Integer> refundAmounts() {
		return jdbcTemplate.queryForList("SELECT amount FROM refund ORDER BY id", Integer.class);
	}

	private List<String> refundStatuses() {
		return jdbcTemplate.queryForList("SELECT status FROM refund ORDER BY id", String.class);
	}

	private List<String> orderStatuses() {
		return jdbcTemplate.queryForList("SELECT status FROM orders ORDER BY id", String.class);
	}

	private List<String> groupStatuses() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group ORDER BY id", String.class);
	}

	private int sold() {
		return jdbcTemplate.queryForObject("SELECT sold FROM sale_form WHERE id = ?",
				Integer.class, setup.saleFormId());
	}
}
