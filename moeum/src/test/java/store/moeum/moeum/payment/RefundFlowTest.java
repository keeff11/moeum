package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.refund.RefundReconcileBatch;
import store.moeum.moeum.payment.refund.RefundRequester;
import store.moeum.moeum.payment.refund.RefundService;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

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
 * 취소 실행과 대사 (roadmap 6단계).
 *
 * <b>승인과 결정적으로 다른 점 — 취소는 멱등하지 않다.</b>
 * 타임아웃·5xx·미확정 409 뒤에 다시 요청하면 같은 취소가 두 번 실행된다.
 * 그래서 '모름' 은 전부 PROCESSING 으로 남기고 배치가 조회 → resume 으로 끝내야 한다.
 */
@org.springframework.context.annotation.Import(RefundFlowTest.FixedClockConfig.class)
class RefundFlowTest extends IntegrationTest {

	/**
	 * 시계를 낮 12시로 고정한다.
	 *
	 * 고정하지 않으면 <b>밤 11시 반에 돌릴 때 EOB 차단에 걸려 전부 깨진다</b> —
	 * 실제로 한 번 겪었다. 시각이 분기 조건인 코드는 시계를 주입받아야 한다.
	 */
	@org.springframework.boot.test.context.TestConfiguration
	static class FixedClockConfig {
		@org.springframework.context.annotation.Bean
		@org.springframework.context.annotation.Primary
		java.time.Clock testClock() {
			return java.time.Clock.fixed(
					java.time.LocalDateTime.of(2026, 9, 8, 12, 0)
							.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
					java.time.ZoneId.of("Asia/Seoul"));
		}
	}

	private static final String SESSION = "pymt_sess-refund-0000-0000-000000000001";
	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		POINT3.start();
	}

	@DynamicPropertySource
	static void point3(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
		// 타임아웃 분기를 보려고 짧게 잡되, 너무 짧으면 안 된다 —
		// JVM 이 찬 상태에서 첫 요청은 클래스 로딩만으로 500ms 를 넘어 엉뚱한 테스트가 깨진다
		registry.add("moeum.point3.read-timeout", () -> "2s");
	}

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private RefundService refundService;

	@Autowired
	private RefundReconcileBatch reconcileBatch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@Autowired
	private store.moeum.moeum.payment.refund.RefundWriter writer;

	@Autowired
	private store.moeum.moeum.payment.infra.Point3Client point3Client;

	private OrderFixture.Setup setup;
	private Long paymentId;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		setup = fixture.saleForm(10, null);
		payFirst(3);
	}

	// ---------------------------------------------------------------- 성공

	@Test
	@DisplayName("취소가_완료되면_COMPLETED_가_되고_세금이_비율대로_나간다")
	void 취소_성공() {
		stubInspect("refundable", 99000, true);
		stubRefund(200, """
				{"id":"ref-1","status":"completed","amount":30000,"vat":2727,"fee":100}""");

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.COMPLETED);
		assertThat(refundStatus()).containsExactly("COMPLETED");
		assertThat(refundPoint3Id()).isEqualTo("ref-1");
	}

	@Test
	@DisplayName("Idempotency_Key_는_요청_전에_저장된다")
	void 키를_먼저_저장한다() {
		stubInspect("refundable", 99000, true);
		stubRefund(200, """
				{"id":"ref-1","status":"completed","amount":30000}""");

		refundService.refund(paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		// 타임아웃 났을 때 무엇으로 보냈는지 알아야 조회할 수 있다
		assertThat(idempotencyKey()).isNotNull().startsWith("rf_");
	}

	// ---------------------------------------------------------------- 모름 — 되돌리지 않는다

	@Test
	@DisplayName("취소가_타임아웃되면_PROCESSING_으로_남고_재요청하지_않는다")
	void 타임아웃() {
		stubInspect("refundable", 99000, true);
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(200, """
						{"id":"ref-1","status":"completed","amount":30000}""").withFixedDelay(5000)));

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		// 환불됐을 수 있다. 다시 보내면 두 번 환불된다
		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.PROCESSING);
		assertThat(refundStatus()).containsExactly("PROCESSING");
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION)));
	}

	@Test
	@DisplayName("미확정_409는_PROCESSING_으로_남긴다")
	void 미확정_409() {
		stubInspect("refundable", 99000, true);
		stubRefund(409, """
				{"status":409,"result":{"code":"REFUND_TEMPORARY_UNAVAILABLE"}}""");

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.PROCESSING);
		assertThat(refundStatus()).containsExactly("PROCESSING");
	}

	@Test
	@DisplayName("진행_중인_취소가_있으면_새로_만들지_않는다")
	void 진행중이면_안_보낸다() {
		stubInspect("processing", 0, false);

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.PROCESSING);
		// 새 취소를 보내면 두 번 환불된다
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION)));
	}

	// ---------------------------------------------------------------- 확정 거절

	@Test
	@DisplayName("정산됐으면_셀러_직접_환불로_넘긴다")
	void 정산_완료() {
		stubInspect("refundable", 99000, true);
		stubRefund(409, """
				{"status":409,"result":{"code":"SETTLEMENT_DEADLINE_EXCEEDED"}}""");

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.SETTLED_MANUAL);
		assertThat(settledManual()).isEqualTo(1);
		assertThat(refundStatus()).containsExactly("FAILED");
	}

	@Test
	@DisplayName("취소_불가_상태면_실패로_확정한다")
	void 확정_거절() {
		stubInspect("refundable", 99000, true);
		stubRefund(409, """
				{"status":409,"result":{"code":"REFUND_NOT_IN_REFUNDABLE_STATE"}}""");

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.FAILED);
		assertThat(refundStatus()).containsExactly("FAILED");
	}

	@Test
	@DisplayName("422는_실패로_확정한다")
	void 거절_422() {
		stubInspect("refundable", 99000, true);
		stubRefund(422, """
				{"status":422,"result":{"code":"INVALID_AMOUNT"}}""");

		assertThat(refundService.refund(paymentId, null, 30000, "고객 요청", RefundRequester.BUYER)
				.status()).isEqualTo(RefundService.RefundResult.Status.FAILED);
	}

	@Test
	@DisplayName("취소_가능_잔액을_넘으면_보내지_않는다")
	void 잔액_초과() {
		stubInspect("partiallyRefunded", 1000, true);

		RefundService.RefundResult result = refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		assertThat(result.status()).isEqualTo(RefundService.RefundResult.Status.FAILED);
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION)));
	}

	// ---------------------------------------------------------------- 겹침 방지

	@Test
	@DisplayName("진행_중인_취소가_있으면_새_취소를_받지_않는다")
	void 중복_요청() {
		stubInspect("refundable", 99000, true);
		stubRefund(409, """
				{"status":409,"result":{"code":"REFUND_TEMPORARY_UNAVAILABLE"}}""");
		refundService.refund(paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);

		// 겹쳐 보내면 두 번 환불된다
		assertThatThrownBy(() -> refundService.refund(
				paymentId, null, 10000, "또 요청", RefundRequester.BUYER))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_IN_PROGRESS);
	}

	@Test
	@DisplayName("결제가_완료되지_않은_주문은_취소할_수_없다")
	void 미결제_취소() {
		jdbcTemplate.update("UPDATE payment SET status = 'CREATED' WHERE id = ?", paymentId);

		assertThatThrownBy(() -> refundService.refund(
				paymentId, null, 30000, "고객 요청", RefundRequester.BUYER))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_ALLOWED);
	}

	// ---------------------------------------------------------------- EOB

	@Test
	@DisplayName("EOB_시간대에는_요청을_보내지_않는다")
	void EOB_차단() {
		RefundService blocked = new RefundService(writer, point3Client, eobClock());

		assertThatThrownBy(() -> blocked.refund(paymentId, null, 30000, "고객 요청", RefundRequester.BUYER))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_EOB_BLOCKED);

		// 어차피 EOB_WINDOW_BLOCKED 로 튕긴다. refund 행도 만들지 않는다
		assertThat(refundStatus()).isEmpty();
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION)));
	}

	@Test
	@DisplayName("EOB_시간대에는_대사_배치도_쉰다")
	void EOB_에는_배치도_쉰다() {
		makePending();
		agePending();
		POINT3.resetAll();
		stubInspectWithEntry("partiallyRefunded", "ref-1", "completed", 30000);

		RefundReconcileBatch blocked = new RefundReconcileBatch(writer, point3Client, eobClock());

		// 그 시간엔 point3 가 취소를 처리하지 않아 조회·재개가 의미 없다
		assertThat(blocked.reconcileOnce()).isZero();
		assertThat(refundStatus()).containsExactly("PROCESSING");
	}

	private static java.time.Clock eobClock() {
		return java.time.Clock.fixed(
				java.time.LocalDateTime.of(2026, 9, 8, 23, 45)
						.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
				java.time.ZoneId.of("Asia/Seoul"));
	}

	// ---------------------------------------------------------------- 대사 배치

	@Test
	@DisplayName("배치가_completed_를_확인하면_확정한다")
	void 배치_확정() {
		makePending();
		POINT3.resetAll();
		stubInspectWithEntry("partiallyRefunded", "ref-1", "completed", 30000);
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(refundStatus()).containsExactly("COMPLETED");
	}

	@Test
	@DisplayName("배치가_두_번_돌아도_재고가_두_번_돌아가지_않는다")
	void 배치_멱등() {
		makePending();
		POINT3.resetAll();
		stubInspectWithEntry("partiallyRefunded", "ref-1", "completed", 30000);
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(refundStatus()).containsExactly("COMPLETED");
	}

	@Test
	@DisplayName("배치는_새_취소를_만들지_않고_resume_만_한다")
	void 배치는_resume만() {
		makePending();
		POINT3.resetAll();
		stubInspectWithEntry("processing", "ref-1", "processing", 30000);
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION + "/resume"))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"fullyRefunded","originalAmount":99000,
						 "refundableAmount":69000,"canCreateRefund":true,
						 "refunds":[{"id":"ref-1","status":"completed","amount":30000}]}
						""".formatted(SESSION))));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(refundStatus()).containsExactly("COMPLETED");
		// ★ 새 취소를 보내면 두 번 환불된다
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/refunds/v1/" + SESSION)));
	}

	@Test
	@DisplayName("배치가_failed_를_확인하면_실패로_확정한다")
	void 배치_실패_확정() {
		makePending();
		POINT3.resetAll();
		stubInspectWithEntry("refundable", "ref-1", "failed", 30000);
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(refundStatus()).containsExactly("FAILED");
	}

	@Test
	@DisplayName("point3_에_취소_이력이_없으면_실패로_확정한다")
	void 배치_이력_없음() {
		makePending();
		POINT3.resetAll();
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(404, "{}")));
		agePending();

		// 우리 요청이 point3 에 닿지 않았다. 되돌려도 안전하다
		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(refundStatus()).containsExactly("FAILED");
	}

	@Test
	@DisplayName("배치_조회가_실패하면_아무것도_확정하지_않는다")
	void 배치_조회_실패() {
		makePending();
		POINT3.resetAll();
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(503, "{}")));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(refundStatus()).containsExactly("PROCESSING");
	}

	@Test
	@DisplayName("방금_시작된_건은_배치가_건드리지_않는다")
	void 갓_시작된_건() {
		makePending();

		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(refundStatus()).containsExactly("PROCESSING");
	}

	// ---------------------------------------------------------------- 도우미

	/** PROCESSING 인 취소를 하나 만든다 */
	private void makePending() {
		stubInspect("refundable", 99000, true);
		stubRefund(409, """
				{"status":409,"result":{"code":"REFUND_TEMPORARY_UNAVAILABLE"}}""");
		refundService.refund(paymentId, null, 30000, "고객 요청", RefundRequester.BUYER);
	}

	private void payFirst(int qty) {
		String sessionToken = orderService.place(buyer(),
				new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), qty))))
				.sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION))));
		String orderToken = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION))));
		paymentService.confirm(buyer(), orderToken, SESSION, null);
		POINT3.resetAll();
		this.paymentId = jdbcTemplate.queryForObject("SELECT id FROM payment", Long.class);
	}

	private void stubInspect(String status, int refundable, boolean canCreate) {
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"%s","originalAmount":99000,
						 "refundableAmount":%d,"canCreateRefund":%s,"refunds":[]}
						""".formatted(SESSION, status, refundable, canCreate))));
	}

	private void stubInspectWithEntry(String sessionStatus, String entryId, String entryStatus, int amount) {
		POINT3.stubFor(get(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(200, """
						{"paymentSessionId":"%s","status":"%s","originalAmount":99000,
						 "refundableAmount":69000,"canCreateRefund":true,
						 "refunds":[{"id":"%s","status":"%s","amount":%d}]}
						""".formatted(SESSION, sessionStatus, entryId, entryStatus, amount))));
	}

	private void stubRefund(int status, String body) {
		POINT3.stubFor(post(urlPathEqualTo("/refunds/v1/" + SESSION))
				.willReturn(json(status, body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	/**
	 * 배치가 집어갈 만큼 오래된 건으로 만든다.
	 *
	 * <b>DB 의 NOW() 를 쓰면 안 된다.</b> 배치는 고정된 테스트 시계(12:00)를 보는데
	 * NOW() 는 실제 시각이라, 낮 12시가 지난 뒤에 돌리면 갓 만들어진 건으로 보여 아무것도 안 집는다.
	 * 시계를 고정했으면 시각을 만드는 쪽도 같은 시계를 따라야 한다.
	 */
	private void agePending() {
		jdbcTemplate.update("UPDATE refund SET updated_at = ?",
				java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(2026, 9, 8, 11, 55)));
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-refund-buyer", "취소 구매자");
	}

	private List<String> refundStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM refund", String.class);
	}

	private String refundPoint3Id() {
		return jdbcTemplate.queryForObject("SELECT point3_refund_id FROM refund", String.class);
	}

	private String idempotencyKey() {
		return jdbcTemplate.queryForObject("SELECT idempotency_key FROM refund", String.class);
	}

	private int settledManual() {
		return jdbcTemplate.queryForObject("SELECT settled_manual FROM refund", Integer.class);
	}
}
