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
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.refund.OrderRefundService;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;
import store.moeum.moeum.payment.refund.dto.RefundableResponse;
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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 구매자 취소 API — 정책 층 (roadmap 6단계, D-025).
 *
 * {@link store.moeum.moeum.payment.refund.RefundService} 는 "금액 하나를 point3 에서 뺀다" 까지만 한다.
 * 여기서 확인하는 것은 그 위의 번역이다 — <b>구매자가 누르는 "취소" 한 번이
 * 결제 취소 몇 건이 되고, 배송비는 어느 쪽에 붙고, 언제까지 열려 있는가.</b>
 *
 * 폼 두 개짜리 묶음을 쓴다. 하나만 취소하는 경우와 전부 취소하는 경우가 갈리는 최소 구성이다.
 */
@Import(OrderRefundApiTest.FixedClockConfig.class)
class OrderRefundApiTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 낮 12시로 고정한다. 고정하지 않으면 밤 11시 반에 돌릴 때 EOB 차단으로 전부 깨진다 */
	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(LocalDateTime.of(2026, 9, 8, 12, 0).atZone(KST).toInstant(), KST);
		}
	}

	private static final String FIRST_SESSION = "pymt_sess-oref-first-0000-00000001";
	private static final String SECOND_SESSION = "pymt_sess-oref-secnd-0000-00000002";

	/** 옵션 A 1차금 20,000 · 2차금 12,000 / 셀러 배송비 3,000 (OrderFixture) */
	private static final int DEPOSIT1 = 20000;
	private static final int DEPOSIT2 = 12000;
	private static final int SHIPPING = 3000;

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
	private SaleFormService saleFormService;

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
		fixture.buyerWithAddress("kakao-refund-api-buyer", "취소수령");
		formA = fixture.saleForm(10, null);
		formB = fixture.saleFormOfSameSeller(formA, 10);
		sellerKakaoId = sellerRepository.findById(formA.sellerId()).orElseThrow().getKakaoId();

		// 폼 A 2개 + 폼 B 1개 → 1차금 60,000 / 2차금 36,000 + 배송비 3,000
		orderToken = payFirst(List.of(
				new OrderCreateRequest.Item(formA.optionId(), 2),
				new OrderCreateRequest.Item(formB.optionId(), 1)));
	}

	// ---------------------------------------------------------------- 배송비

	@Test
	@DisplayName("한_폼만_취소하면_배송비는_유지된다")
	void 한_폼만_취소() {
		paySecond();
		stubRefundable();
		stubRefundOk();

		OrderRefundResponse response = orderRefundService.refund(buyer(), orderToken, orderIdOf(formA), "단순 변심");

		assertThat(response.status()).isEqualTo(OrderRefundResponse.Status.COMPLETED);
		// 나머지 폼은 그대로 배송된다. 배송비를 돌려주면 셀러가 그만큼 손해다
		assertThat(refundAmounts()).containsExactlyInAnyOrder(DEPOSIT1 * 2, DEPOSIT2 * 2);
		assertThat(response.refundedAmount()).isEqualTo(DEPOSIT1 * 2 + DEPOSIT2 * 2);
	}

	@Test
	@DisplayName("남은_폼을_전부_취소하면_배송비도_함께_환불된다")
	void 전부_취소() {
		paySecond();
		stubRefundable();
		stubRefundOk();

		OrderRefundResponse response = orderRefundService.refund(buyer(), orderToken, null, "단순 변심");

		assertThat(response.status()).isEqualTo(OrderRefundResponse.Status.COMPLETED);
		assertThat(refundAmounts()).containsExactlyInAnyOrder(DEPOSIT1 * 3, DEPOSIT2 * 3 + SHIPPING);
	}

	@Test
	@DisplayName("한_폼을_먼저_취소한_뒤_남은_폼을_취소하면_그때_배송비가_돌아간다")
	void 나눠서_전부_취소() {
		paySecond();
		stubRefundable();
		stubRefundOk();

		orderRefundService.refund(buyer(), orderToken, orderIdOf(formA), "단순 변심");
		orderRefundService.refund(buyer(), orderToken, orderIdOf(formB), "단순 변심");

		// 두 번째 취소 시점에는 남은 폼이 없다 — 배송비가 그때 붙는다
		assertThat(refundAmounts()).containsExactlyInAnyOrder(
				DEPOSIT1 * 2, DEPOSIT2 * 2,
				DEPOSIT1, DEPOSIT2 + SHIPPING);
		assertThat(groupStatus()).isEqualTo("CANCELED");
	}

	// ---------------------------------------------------------------- 1차금 / 2차금 조합

	@Test
	@DisplayName("2차금이_아직_결제되지_않았으면_1차금만_취소한다")
	void 이차금_전() {
		stubRefundable();
		stubRefundOk();

		OrderRefundResponse response = orderRefundService.refund(buyer(), orderToken, null, "단순 변심");

		assertThat(response.details()).hasSize(1);
		assertThat(response.details().get(0).phase()).isEqualTo("FIRST");
		assertThat(refundAmounts()).containsExactly(DEPOSIT1 * 3);
	}

	@Test
	@DisplayName("1차금이_확정_거절되면_2차금은_보내지_않는다")
	void 일차금_거절() {
		paySecond();
		stubRefundable();
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + FIRST_SESSION))
				.willReturn(json(409, "{\"status\":409,\"result\":{\"code\":\"REFUND_NOT_IN_REFUNDABLE_STATE\"}}")));
		stubRefundOkFor(SECOND_SESSION);

		OrderRefundResponse response = orderRefundService.refund(buyer(), orderToken, null, "단순 변심");

		assertThat(response.status()).isEqualTo(OrderRefundResponse.Status.FAILED);
		// 잔금만 돌려주면 상품값은 받은 채 배송비만 환불한 꼴이 된다
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SECOND_SESSION)));
	}

	@Test
	@DisplayName("한_건이라도_미확정이면_전체가_PROCESSING_이다")
	void 하나가_미확정() {
		paySecond();
		stubRefundable();
		stubRefundOkFor(FIRST_SESSION);
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SECOND_SESSION))
				.willReturn(json(409, "{\"status\":409,\"result\":{\"code\":\"REFUND_TEMPORARY_UNAVAILABLE\"}}")));

		OrderRefundResponse response = orderRefundService.refund(buyer(), orderToken, null, "단순 변심");

		// 성공한 쪽만 보고 COMPLETED 라고 하면 남은 한 건이 조용히 묻힌다
		assertThat(response.status()).isEqualTo(OrderRefundResponse.Status.PROCESSING);
		assertThat(refundStatuses()).containsExactlyInAnyOrder("COMPLETED", "PROCESSING");
	}

	// ---------------------------------------------------------------- 주문 · 재고

	@Test
	@DisplayName("취소가_완료되면_주문이_CANCELED_가_되고_재고는_한_번만_돌아간다")
	void 재고는_한_번만() {
		paySecond();
		int soldBefore = sold(formA.saleFormId());
		stubRefundable();
		stubRefundOk();

		orderRefundService.refund(buyer(), orderToken, orderIdOf(formA), "단순 변심");

		assertThat(orderStatus(orderIdOf(formA))).isEqualTo("CANCELED");
		// 1차금·2차금 두 건이 확정된다. 양쪽에서 되돌리면 재고가 두 번 돌아간다
		assertThat(sold(formA.saleFormId())).isEqualTo(soldBefore - 2);
	}

	@Test
	@DisplayName("한_폼만_취소된_묶음은_묶음_상태를_내리지_않는다")
	void 묶음은_살아_있다() {
		stubRefundable();
		stubRefundOk();

		orderRefundService.refund(buyer(), orderToken, orderIdOf(formA), "단순 변심");

		// 남은 폼은 계속 배송돼야 하고, 묶음을 내리면 2차금 청구 대상에서도 빠진다
		assertThat(groupStatus()).isEqualTo("PAID");
		assertThat(orderStatus(orderIdOf(formB))).isEqualTo("PAID");
	}

	// ---------------------------------------------------------------- 취소 구간

	@Test
	@DisplayName("발주가_시작된_공구는_취소할_수_없다")
	void 발주_이후() {
		jdbcTemplate.update("UPDATE orders SET status = 'PRODUCING'");

		assertThatThrownBy(() -> orderRefundService.refund(buyer(), orderToken, null, "단순 변심"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_ALLOWED);

		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + FIRST_SESSION)));
	}

	@Test
	@DisplayName("단독판매는_발주_이후에도_발송_전이면_취소할_수_있다")
	void 단독은_더_늦게까지() {
		jdbcTemplate.update("UPDATE sale_form SET sale_type = 'SOLO'");
		jdbcTemplate.update("UPDATE orders SET status = 'PRODUCING'");
		stubRefundable();
		stubRefundOk();

		assertThat(orderRefundService.refund(buyer(), orderToken, null, "단순 변심").status())
				.isEqualTo(OrderRefundResponse.Status.COMPLETED);
	}

	// ---------------------------------------------------------------- 소유권

	@Test
	@DisplayName("남의_주문은_취소할_수_없다")
	void 남의_주문() {
		SessionUser other = new SessionUser("kakao-somebody-else", "남");

		assertThatThrownBy(() -> orderRefundService.refund(other, orderToken, null, "단순 변심"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	@DisplayName("묶음에_없는_주문_id는_취소할_수_없다")
	void 남의_폼_id() {
		assertThatThrownBy(() -> orderRefundService.refund(buyer(), orderToken, 999_999L, "단순 변심"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.ORDER_GROUP_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 조회

	@Test
	@DisplayName("취소_가능_조회는_폼별_환불_예정액을_알려준다")
	void 조회() {
		paySecond();

		RefundableResponse response = orderRefundService.refundable(buyer(), orderToken);

		assertThat(response.refundable()).isTrue();
		assertThat(response.items()).hasSize(2);
		assertThat(response.items()).allSatisfy(item -> assertThat(item.refundable()).isTrue());
		assertThat(response.shippingFee()).isEqualTo(SHIPPING);
		// 남은 폼을 전부 취소해야만 배송비가 함께 돌아간다
		assertThat(response.shippingFeeRefundable()).isTrue();
		assertThat(response.items().stream().mapToInt(RefundableResponse.Item::refundAmount).sum())
				.isEqualTo(DEPOSIT1 * 3 + DEPOSIT2 * 3);
	}

	@Test
	@DisplayName("취소_가능_조회는_point3를_부르지_않는다")
	void 조회는_부작용이_없다() {
		orderRefundService.refundable(buyer(), orderToken);

		// 주문 상세 화면에서 그대로 불러도 되게 해야 한다
		assertThat(POINT3.getAllServeEvents()).isEmpty();
	}

	@Test
	@DisplayName("발주된_폼은_조회에서_사유와_함께_잠긴다")
	void 조회_차단_사유() {
		Long blocked = orderIdOf(formA);
		jdbcTemplate.update("UPDATE orders SET status = 'PRODUCING' WHERE id = ?", blocked);

		RefundableResponse response = orderRefundService.refundable(buyer(), orderToken);

		assertThat(response.refundable()).isTrue();   // 폼 B 는 아직 취소할 수 있다
		assertThat(itemOf(response, blocked).refundable()).isFalse();
		assertThat(itemOf(response, blocked).blockedReason()).contains("발주");
		// 한 폼이 잠겨 있으면 전부 취소가 불가능하므로 배송비도 돌아가지 않는다
		assertThat(response.shippingFeeRefundable()).isFalse();
	}

	// ---------------------------------------------------------------- 도우미

	private String payFirst(List<OrderCreateRequest.Item> items) {
		String sessionToken = orderService.place(buyer(), new OrderCreateRequest(items)).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, "{\"id\":\"" + FIRST_SESSION + "\",\"status\":\"created\",\"amount\":60000,"
						+ "\"supplyAmount\":54546,\"vat\":5454,\"taxFreeAmount\":0,\"currency\":\"KRW\"}")));
		String token = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + FIRST_SESSION))
				.willReturn(json(200, "{\"id\":\"" + FIRST_SESSION + "\",\"status\":\"captured\"}")));
		paymentService.confirm(buyer(), token, FIRST_SESSION, null);
		POINT3.resetAll();
		return token;
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
		POINT3.resetAll();

		// 입고(ARRIVED)는 공구에서 발주 이후다. 취소 구간을 보려면 마감 상태로 되돌려 둔다
		jdbcTemplate.update("UPDATE orders SET status = 'CLOSED'");
	}

	private void stubRefundable() {
		for (String session : List.of(FIRST_SESSION, SECOND_SESSION)) {
			POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + session))
					.willReturn(json(200, "{\"paymentSessionId\":\"" + session + "\",\"status\":\"refundable\","
							+ "\"originalAmount\":99000,\"refundableAmount\":99000,"
							+ "\"canCreateRefund\":true,\"refunds\":[]}")));
		}
	}

	private void stubRefundOk() {
		stubRefundOkFor(FIRST_SESSION);
		stubRefundOkFor(SECOND_SESSION);
	}

	private void stubRefundOkFor(String session) {
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + session))
				.willReturn(json(200, "{\"id\":\"ref-" + session + "\",\"status\":\"completed\",\"amount\":1000}")));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-refund-api-buyer", "취소 구매자");
	}

	private static RefundableResponse.Item itemOf(RefundableResponse response, Long orderId) {
		return response.items().stream()
				.filter(item -> orderId.equals(item.orderId()))
				.findFirst().orElseThrow();
	}

	private Long orderIdOf(OrderFixture.Setup form) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM orders WHERE sale_form_id = ?", Long.class, form.saleFormId());
	}

	private List<Integer> refundAmounts() {
		return jdbcTemplate.queryForList("SELECT amount FROM refund ORDER BY id", Integer.class);
	}

	private List<String> refundStatuses() {
		return jdbcTemplate.queryForList("SELECT status FROM refund ORDER BY id", String.class);
	}

	private String orderStatus(Long orderId) {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
	}

	private String groupStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM order_group", String.class);
	}

	private int sold(Long saleFormId) {
		return jdbcTemplate.queryForObject("SELECT sold FROM sale_form WHERE id = ?", Integer.class, saleFormId);
	}
}
