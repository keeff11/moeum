package store.moeum.moeum.outbox;

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
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.payment.refund.OrderRefundService;
import store.moeum.moeum.saleform.SaleFormService;
import store.moeum.moeum.seller.domain.SellerRepository;
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

/**
 * 실제 흐름이 알림을 적재하는지 (roadmap 7단계, D-012).
 *
 * 릴레이가 아무리 튼튼해도 <b>적재하는 쪽이 비어 있으면 아무 일도 일어나지 않는다.</b>
 * 여기서 보는 것은 배관이 아니라 그 반대쪽 끝이다 —
 * 결제·입고·취소가 실제로 outbox 행을 남기는가, 그리고 두 번 남기지 않는가.
 */
@Import(OutboxEventTest.FixedClockConfig.class)
class OutboxEventTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(LocalDateTime.of(2026, 9, 8, 12, 0).atZone(KST).toInstant(), KST);
		}
	}

	private static final String FIRST_SESSION = "pymt_sess-obx-first-0000-00000001";
	private static final String SECOND_SESSION = "pymt_sess-obx-secnd-0000-00000002";

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
	private OrderRefundService orderRefundService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup formA;
	private OrderFixture.Setup formB;
	private String sellerKakaoId;
	private String orderToken;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		jdbcTemplate.execute("DELETE FROM outbox");
		formA = fixture.saleForm(10, null);
		formB = fixture.saleFormOfSameSeller(formA, 10);
		sellerKakaoId = sellerRepository.findById(formA.sellerId()).orElseThrow().getKakaoId();
	}

	// ---------------------------------------------------------------- 결제

	@Test
	@DisplayName("1차금이_확정되면_ORDER_PAID_가_적재된다")
	void 일차금() {
		payFirst();

		assertThat(events()).containsExactly("ORDER_PAID");
		// 알림톡이 누구에게 무엇을 보낼지 알 만큼은 담는다
		assertThat(payload()).contains("orderToken").contains("buyerId").contains("amount");
	}

	@Test
	@DisplayName("payload_에_토큰이나_payerId_가_들어가지_않는다")
	void 민감정보() {
		payFirst();

		// DB 에 남고 실패하면 로그에도 실린다 (CLAUDE.md 규칙 10)
		assertThat(payload())
				.doesNotContain("payerId")
				.doesNotContain("sessionId")
				.doesNotContain(FIRST_SESSION);
	}

	@Test
	@DisplayName("같은_결제를_다시_확정해도_알림이_쌓이지_않는다")
	void 확정_멱등() {
		payFirst();
		// 대사 배치가 이미 확정된 결제를 다시 훑는 상황이다
		paymentService.confirm(buyer(), orderToken, FIRST_SESSION, null);

		// 멱등 가드 바깥에서 적재하면 여기서 두 건이 된다
		assertThat(events()).containsExactly("ORDER_PAID");
	}

	// ---------------------------------------------------------------- 2차금 청구

	@Test
	@DisplayName("모든_폼이_입고돼야_SECOND_PAYMENT_DUE_가_적재된다")
	void 이차금_청구() {
		payFirst();
		saleFormService.markArrived(sellerKakaoId, formA.saleFormId());

		// 배송비가 묶음당 1회라 일부만 입고됐다고 청구할 수 없다
		assertThat(events()).containsExactly("ORDER_PAID");

		saleFormService.markArrived(sellerKakaoId, formB.saleFormId());

		// 이 알림이 곧 결제 요청이다
		assertThat(events()).containsExactly("ORDER_PAID", "SECOND_PAYMENT_DUE");
	}

	@Test
	@DisplayName("같은_폼을_다시_입고_처리해도_청구_알림이_쌓이지_않는다")
	void 입고_멱등() {
		payFirst();
		saleFormService.markArrived(sellerKakaoId, formA.saleFormId());
		saleFormService.markArrived(sellerKakaoId, formB.saleFormId());
		saleFormService.markArrived(sellerKakaoId, formB.saleFormId());

		// 두 번 가면 구매자가 두 번 결제하러 온다
		assertThat(events()).containsExactly("ORDER_PAID", "SECOND_PAYMENT_DUE");
	}

	@Test
	@DisplayName("2차금이_확정되면_SECOND_PAID_가_적재된다")
	void 이차금_확정() {
		payFirst();
		paySecond();

		assertThat(events()).containsExactly("ORDER_PAID", "SECOND_PAYMENT_DUE", "SECOND_PAID");
	}

	// ---------------------------------------------------------------- 취소

	@Test
	@DisplayName("취소가_완료되면_REFUND_COMPLETED_가_한_번만_적재된다")
	void 취소() {
		payFirst();
		paySecond();
		stubRefund();

		orderRefundService.refund(buyer(), orderToken, null, "단순 변심");

		// 취소 한 번이 1차금·2차금 두 건의 환불로 나간다. 양쪽에서 적재하면 알림이 두 번 간다
		assertThat(events()).containsExactly(
				"ORDER_PAID", "SECOND_PAYMENT_DUE", "SECOND_PAID", "REFUND_COMPLETED");
	}

	// ---------------------------------------------------------------- 도우미

	private void payFirst() {
		String sessionToken = orderService.place(buyer(), new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(formA.optionId(), 2),
				new OrderCreateRequest.Item(formB.optionId(), 1)))).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, "{\"id\":\"" + FIRST_SESSION + "\",\"status\":\"created\",\"amount\":60000,"
						+ "\"supplyAmount\":54546,\"vat\":5454,\"taxFreeAmount\":0,\"currency\":\"KRW\"}")));
		orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + FIRST_SESSION))
				.willReturn(json(200, "{\"id\":\"" + FIRST_SESSION + "\",\"status\":\"captured\"}")));
		paymentService.confirm(buyer(), orderToken, FIRST_SESSION, null);
	}

	private void paySecond() {
		saleFormService.markArrived(sellerKakaoId, formA.saleFormId());
		saleFormService.markArrived(sellerKakaoId, formB.saleFormId());
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, "{\"id\":\"" + SECOND_SESSION + "\",\"status\":\"created\",\"amount\":39000,"
						+ "\"supplyAmount\":35455,\"vat\":3545,\"taxFreeAmount\":0,\"currency\":\"KRW\"}")));
		paymentService.paySecond(buyer(), orderToken);
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SECOND_SESSION))
				.willReturn(json(200, "{\"id\":\"" + SECOND_SESSION + "\",\"status\":\"captured\"}")));
		paymentService.confirmSecond(buyer(), orderToken, SECOND_SESSION);
	}

	private void stubRefund() {
		// 입고(ARRIVED)는 공구에서 발주 이후다. 취소 구간을 보려면 마감 상태로 되돌려 둔다
		jdbcTemplate.update("UPDATE orders SET status = 'CLOSED'");
		for (String session : List.of(FIRST_SESSION, SECOND_SESSION)) {
			POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + session))
					.willReturn(json(200, "{\"paymentSessionId\":\"" + session + "\",\"status\":\"refundable\","
							+ "\"originalAmount\":99000,\"refundableAmount\":99000,"
							+ "\"canCreateRefund\":true,\"refunds\":[]}")));
			POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + session))
					.willReturn(json(200, "{\"id\":\"ref-" + session
							+ "\",\"status\":\"completed\",\"amount\":1000}")));
		}
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-outbox-buyer", "알림 구매자");
	}

	private List<String> events() {
		return jdbcTemplate.queryForList("SELECT event_type FROM outbox ORDER BY id", String.class);
	}

	private String payload() {
		return jdbcTemplate.queryForObject("SELECT payload FROM outbox ORDER BY id LIMIT 1", String.class);
	}
}
