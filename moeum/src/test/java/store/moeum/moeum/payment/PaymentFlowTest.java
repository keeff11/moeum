package store.moeum.moeum.payment;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.alert.AlertCooldown;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;
import org.springframework.test.util.ReflectionTestUtils;
import store.moeum.moeum.order.HoldExpiryBatch;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.payment.domain.CapturedAfterFailureException;
import store.moeum.moeum.payment.domain.PaymentActor;
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.payment.infra.Point3Session;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;
import store.moeum.moeum.support.RecordingAlertSender;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1차금 결제 흐름 (roadmap 4단계 필수 테스트).
 *
 * <b>여기서 지키는 것은 하나다 — 결과를 모르면 아무것도 되돌리지 않는다.</b>
 * 타임아웃·5xx 에서 홀드를 풀면 그 재고가 남에게 팔리고, 실제로 출금됐다면
 * 구매자는 돈만 나간 상태가 된다. 대사 배치가 확정할 때까지 기다려야 한다.
 *
 * point3 는 WireMock 이다. 실제 API 로는 타임아웃과 5xx 를 요청해서 받을 수 없다.
 */
class PaymentFlowTest extends IntegrationTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-000000000000";
	private static final String SESSION_ID_2 = "pymt_sess-019f0000-0000-7000-9000-000000000001";
	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		POINT3.start();
	}

	@DynamicPropertySource
	static void point3(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
		// 타임아웃 분기를 보려고 짧게 잡되, 너무 짧으면 안 된다 — 클래스의 첫 요청은 JVM 이 덜 데워져
		// 500ms 를 넘겨 엉뚱한 테스트가 세션 생성 타임아웃으로 깨졌다 (RefundFlowTest 와 같은 이유)
		registry.add("moeum.point3.read-timeout", () -> "2s");
		// 커넥션 풀 대기 초과는 운영과 같은 3초로 본다 (D-092). 테스트 프로파일은 기본값 30초다
		registry.add("spring.datasource.hikari.connection-timeout", () -> "3000");
	}

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private PaymentWriter paymentWriter;

	@Autowired
	private PaymentReconcileBatch reconcileBatch;

	@Autowired
	private HoldExpiryBatch holdExpiryBatch;

	@Autowired
	private PaymentPendingAlerter alerter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;
	private String sessionToken;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(10, null);
		OrderGroupResponse group = orderService.place(buyer(), order(3));
		sessionToken = group.sessionToken();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 세션 생성

	@Test
	@DisplayName("세션을_만들면_결제창에_필요한_값이_나오고_홀드는_그대로다")
	void 세션_생성() {
		stubCreateSession();

		PaySessionResponse response = paymentService.pay(buyer(), sessionToken);

		assertThat(response.sessionId()).isEqualTo(SESSION_ID);
		assertThat(response.orderToken()).startsWith("ord_");
		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(groupStatus()).containsExactly("PAY_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("홀드가_만료됐으면_세션을_만들지_않는다")
	void 만료된_홀드로는_세션을_못_만든다() {
		expireHolds();

		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);

		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/payment/v3/session")));
	}

	@Test
	@DisplayName("point3_세션_생성이_실패하면_재시도_가능한_오류로_알리고_홀드는_그대로다")
	void 세션_생성_실패() {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(500, "{}")));

		// 결제창을 못 봤으니 돈이 나갔을 수 없다. 같은 버튼을 다시 누르면 된다
		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.TEMPORARY_ERROR);
		assertThat(held()).isEqualTo(3);

		stubCreateSession();
		assertThat(paymentService.pay(buyer(), sessionToken).sessionId()).isEqualTo(SESSION_ID);
	}

	@Test
	@DisplayName("남의_주문으로는_결제할_수_없다")
	void 남의_주문() {
		assertThatThrownBy(() -> paymentService.pay(other(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.FORBIDDEN);
	}

	// ---------------------------------------------------------------- 승인

	@Test
	@DisplayName("승인이_captured_면_확정되고_재고가_차감된다")
	void 승인_성공() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(held()).isZero();
		assertThat(sold()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("COMMITTED");
	}

	@Test
	@DisplayName("승인_타임아웃이면_홀드를_풀지_않고_CAPTURE_PENDING_을_유지한다")
	void 승인_타임아웃() {
		String orderToken = startPayment();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))
						.withFixedDelay(4000)));   // 읽기 타임아웃(2s)보다 길어야 한다

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 출금됐을 수 있다. 여기서 홀드를 풀면 그 재고가 남에게 팔린다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("HELD");
	}

	@Test
	@DisplayName("승인_5xx_면_되돌리지_않는다")
	void 승인_5xx() {
		String orderToken = startPayment();
		stubCapture(500, "{}");

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("승인_4xx_면_즉시_실패_처리하고_홀드를_해제한다")
	void 승인_4xx() {
		String orderToken = startPayment();
		stubCapture(400, """
				{"code":"INVALID_REQUEST"}""");

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 4xx 는 point3 가 받아들이지 않은 것이라 승인이 일어났을 가능성이 없다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(held()).isZero();
		assertThat(sold()).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	@DisplayName("승인이_401_403_이면_실패로_확정하지_않고_CAPTURE_PENDING_을_유지하며_바로_알린다")
	void 승인_자격증명_거부(int status) {
		String orderToken = startPayment();
		stubCapture(status, "{}");

		RecordingAlertSender sender = swapAlertSender();
		try {
			PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

			// 우리 토큰 · 설정 문제다 (D-082). 고치면 살릴 수 있으니 재고를 넘기지 않는다
			assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
			assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
			assertThat(held()).isEqualTo(3);
			assertThat(holdStatus()).containsExactly("HELD");
			assertThat(sender.sent).hasSize(1);
			assertThat(sender.sent.get(0).level()).isEqualTo(AlertLevel.CRITICAL);
			assertThat(sender.sent.get(0).text()).contains(String.valueOf(status));
		} finally {
			restoreAlertSender();
		}
	}

	@Test
	@DisplayName("승인_401_뒤_토큰을_고치면_대사_배치가_승인을_다시_불러_확정한다")
	void 승인_401_뒤_복구() {
		String orderToken = startPayment();
		stubCapture(401, "{}");
		swapAlertSender();
		try {
			paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		} finally {
			restoreAlertSender();
		}

		// 토큰을 고쳤다. point3 쪽 세션은 구매자 확정 상태로 남아 있다
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("COMMITTED");
	}

	@Test
	@DisplayName("확정은_회원_행을_건드리지_않아_다른_탭이_회원_행을_쥐고_있어도_바로_끝난다")
	void 확정_회원_잠금_무관() throws Exception {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		// 다른 탭의 새 주문이 회원 행을 쥐고 있다 — OrderCreator 의 첫 쿼리와 같은 잠금이다 (D-077).
		// 예전엔 확정이 주문서를 쥔 채 이 행을 기다렸고, 그 탭이 주문서를 기다리면 교착이었다 (D-084)
		PaymentResultResponse result = whileLocked(
				"SELECT id FROM buyer WHERE kakao_id = ? FOR UPDATE", "kakao-payer", () -> {
					long started = System.nanoTime();
					PaymentResultResponse r = paymentService.confirm(buyer(), orderToken, SESSION_ID, "payer-first");
					// 테스트 MySQL 의 락 대기 한도는 5초다. 기다렸다면 그만큼 걸린다
					assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
					return r;
				});

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("COMMITTED");
		// 회원 행을 못 잡은 payerId 저장만 건너뛴다. 2차금에서 본인인증을 한 번 더 할 뿐이다
		assertThat(payerId()).isNull();
	}

	@Test
	@DisplayName("확정이_커밋되면_payerId_를_따로_저장한다")
	void 확정_뒤_payerId_저장() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		paymentService.confirm(buyer(), orderToken, SESSION_ID, "payer-first");

		assertThat(payerId()).isEqualTo("payer-first");
	}

	@Test
	@DisplayName("확정이_재고_행_잠금에_막혀_롤백되면_실패라고_답하지_않고_대사_배치가_확정한다")
	void 확정_잠금_충돌() throws Exception {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		// 다른 주문이 같은 상품의 재고 행을 쥐고 있다. 확정의 홀드 확정(sold 올리기)이
		// 락 대기 초과(테스트 MySQL 5초)로 롤백된다. 교착이면 DeadlockLoserDataAccessException 이고
		// 같은 부모(PessimisticLockingFailureException)다
		PaymentResultResponse result = whileLocked(
				"SELECT id FROM sale_form WHERE id = ? FOR UPDATE", setup.saleFormId(),
				() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, "payer-first"));

		// 돈은 나갔다. 500 으로 실패처럼 보이면 안 된다 (D-083)
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(groupStatus()).containsExactly("PAY_PENDING");
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(held()).isEqualTo(3);

		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("COMMITTED");
	}

	@Test
	@DisplayName("출금된_뒤_커넥션_풀이_바닥나_확정을_못_적으면_실패라고_답하지_않고_대사_배치가_확정한다")
	void 확정_커넥션_대기_초과() throws Exception {
		String orderToken = startPayment();
		// 승인 응답이 오는 사이에 풀을 말린다. 승인 호출은 트랜잭션 밖이라 커넥션을 쥐지 않는다
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID)).withFixedDelay(1000)));

		ExecutorService buyerThread = Executors.newSingleThreadExecutor();
		List<Connection> borrowed = new ArrayList<>();
		PaymentResultResponse result;
		try {
			Future<PaymentResultResponse> confirming = buyerThread.submit(
					() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, "payer-first"));
			awaitPaymentStatus("CAPTURE_PENDING");
			// 다른 요청들이 재고 경합으로 커넥션을 다 쥐고 있는 상황이다 (D-092)
			int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
			for (int i = 0; i < poolSize; i++) {
				borrowed.add(dataSource.getConnection());
			}
			result = confirming.get(15, TimeUnit.SECONDS);
		} finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
			buyerThread.shutdown();
		}

		// 돈은 나갔다. 500 으로 실패처럼 보이면 안 된다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(holdStatus()).containsExactly("HELD");

		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("커넥션_풀이_바닥나면_트랜잭션_시작이_CannotCreateTransactionException으로_실패한다")
	void 커넥션_대기_초과_예외_종류() throws Exception {
		// 전역 처리기가 503 으로, 확정이 PENDING 으로 받는 예외가 실제로 이것인지 본다
		int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
		List<Connection> borrowed = new ArrayList<>();
		try {
			for (int i = 0; i < poolSize; i++) {
				borrowed.add(dataSource.getConnection());
			}
			assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
					.isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
		} finally {
			for (Connection connection : borrowed) {
				connection.close();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	@DisplayName("배치의_승인_재호출이_401_403_이면_실패로_확정하지_않는다")
	void 배치_승인_재호출_자격증명_거부(int status) {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(status, "{}");
		agePending();

		RecordingAlertSender sender = swapAlertSender();
		try {
			assertThat(reconcileBatch.reconcileOnce()).isZero();
			assertThat(sender.sent).hasSize(1);
		} finally {
			restoreAlertSender();
		}
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("HELD");
	}

	@Test
	@DisplayName("같은_주문에_confirm_이_두_번_와도_한_번만_처리된다")
	void confirm_중복() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		PaymentResultResponse second = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(second.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(sold()).isEqualTo(3);
		// 두 번째는 승인을 부르지 않는다
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("홀드가_만료됐으면_승인을_보내지_않는다")
	void 만료된_홀드로는_승인하지_않는다() {
		String orderToken = startPayment();
		expireHolds();

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);

		// 돈이 나가기 전에 막는다 — 나간 뒤 되돌리는 것보다 항상 싸다
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("세션_id_가_다르면_승인하지_않는다")
	void 세션_불일치() {
		String orderToken = startPayment();

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, "pymt_sess-남의것", null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SESSION_MISMATCH);

		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	// ---------------------------------------------------------------- 대사 배치

	@Test
	@DisplayName("CAPTURE_PENDING_상태에서_배치가_captured_를_확인하면_확정한다")
	void 배치가_확정한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");

		// 실제로는 출금돼 있었다
		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("배치가_두_번_확정해도_재고가_한_번만_차감된다")
	void 배치_멱등() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(sold()).isEqualTo(3);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("CAPTURE_PENDING_인데_세션이_committed_면_승인을_다시_호출한다")
	void 배치가_승인을_재호출한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 구매자는 확정했는데 승인이 닿지 않았다
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("배치가_failed_를_확인하면_실패_확정하고_홀드를_푼다")
	void 배치가_실패를_확정한다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubGetSession("""
				{"id":"%s","status":"failed"}""".formatted(SESSION_ID));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(held()).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
	}

	@Test
	@DisplayName("배치_조회가_실패하면_아무것도_확정하지_않는다")
	void 배치_조회_실패() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(503, "{}")));
		agePending();

		assertThat(reconcileBatch.reconcileOnce()).isZero();
		// 모르는 채로 두는 것이 잘못 확정하는 것보다 낫다
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("방금_시작된_건은_배치가_건드리지_않는다")
	void 갓_시작된_건은_건너뛴다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 실시간 confirm 이 아직 진행 중일 수 있다
		assertThat(reconcileBatch.reconcileOnce()).isZero();
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
	}

	@Test
	@DisplayName("대사_배치는_집어간_건을_줄_뒤로_보내_미확정_건을_돌아가며_본다")
	void 미확정_건_순환() {
		Long older = pendingPayment(buyer(), sessionToken, SESSION_ID);
		fixture.buyerWithAddress("kakao-payer2", "이도윤");
		SessionUser secondBuyer = new SessionUser("kakao-payer2", "결제자2");
		String secondToken = orderService.place(secondBuyer, order(2)).sessionToken();
		Long newer = pendingPayment(secondBuyer, secondToken, SESSION_ID_2);

		// older 가 더 오래 멈춰 있다. updated_at 순이면 한 건씩 집을 때 매번 older 만 나온다
		jdbcTemplate.update(
				"UPDATE payment SET updated_at = DATE_SUB(NOW(6), INTERVAL 10 MINUTE) WHERE id = ?", older);
		jdbcTemplate.update(
				"UPDATE payment SET updated_at = DATE_SUB(NOW(6), INTERVAL 5 MINUTE) WHERE id = ?", newer);
		List<LocalDateTime> updatedBefore = updatedAts();

		LocalDateTime threshold = LocalDateTime.now(JpaAuditingConfig.KST).minusMinutes(1);
		assertThat(paymentWriter.claimPending(threshold, 1)).containsExactly(older);
		assertThat(paymentWriter.claimPending(threshold, 1)).containsExactly(newer);
		assertThat(paymentWriter.claimPending(threshold, 1)).containsExactly(older);

		// 멈춤 알림이 updated_at 으로 멈춘 시간을 잰다. 집어갔다고 바뀌면 알림이 울리지 않는다
		assertThat(updatedAts()).isEqualTo(updatedBefore);
	}

	// ---------------------------------------------------------------- 만료 시 구매자 확정 확인 (D-063)

	@Test
	@DisplayName("홀드가_만료돼도_구매자가_확정했으면_풀지_않고_대사가_승인한다")
	void 만료_배치_committed() {
		startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));

		// 결과값이 우리에게 안 닿았다. 구매자 탓이 아니다
		assertThat(holdExpiryBatch.expireOnce()).isZero();
		assertThat(held()).isEqualTo(3);
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");

		// 다음 회차에도 건드리지 않는다 — CAPTURE_PENDING 은 조회에서 빠진다
		assertThat(holdExpiryBatch.expireOnce()).isZero();
		assertThat(held()).isEqualTo(3);

		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		agePending();
		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(held()).isZero();
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("구매자가_확정하지_않았으면_만료_배치가_푼다")
	void 만료_배치_initiated() {
		startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));

		// 구매자가 늦은 것이다
		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		assertThat(held()).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
		assertThat(paymentStatus()).containsExactly("CREATED");
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("만료_배치가_걷은_주문은_이어서_결제하라고_안내하지_않는다")
	void 만료된_주문_조회() {
		String orderToken = startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));
		holdExpiryBatch.expireOnce();

		PaymentResultResponse detail = paymentService.status(buyer(), orderToken);

		// AWAITING_PAYMENT 로 두면 "결제 이어하기" 가 뜨고, 누르면 HOLD_EXPIRED 가 난다
		assertThat(detail.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		assertThat(detail.pendingReason()).isNull();
		assertThat(detail.sessionToken()).isNull();
		assertThat(detail.message()).contains("만료");
	}

	@Test
	@DisplayName("point3_조회가_안_되면_만료_배치가_풀지_않는다")
	void 만료_배치_조회_실패() {
		startPayment();
		expireHolds();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(503, "{}")));

		assertThat(holdExpiryBatch.expireOnce()).isZero();
		assertThat(held()).isEqualTo(3);
		assertThat(paymentStatus()).containsExactly("CREATED");

		// 복구되면 다음 회차에 판단한다
		stubGetSession("""
				{"id":"%s","status":"created"}""".formatted(SESSION_ID));
		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("point3_가_응답하지_않아도_결제창과_무관한_홀드는_제때_푼다")
	void 만료_배치_타임아웃_중_다른_홀드() {
		startPayment();
		// 결제창에 가지 않은 다른 구매자의 주문
		orderService.place(new SessionUser("kakao-leaver", "이탈자"), order(2));
		expireHolds();
		// 지연 응답은 끊긴 연결에 남아 다음 테스트의 요청이 받아 간다. 결과 불명은 5xx 로 낸다
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(503, "{}")));

		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		// 결제창까지 간 3개는 남고, 이탈자의 2개만 풀린다
		assertThat(held()).isEqualTo(3);
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("세션이_없으면_만료_배치가_푼다")
	void 만료_배치_세션_404() {
		startPayment();
		expireHolds();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(404, "{}")));

		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("결제창에_안_간_만료_홀드는_point3_에_묻지_않고_푼다")
	void 만료_배치_세션_없음() {
		expireHolds();

		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		assertThat(held()).isZero();
		POINT3.verify(0, getRequestedFor(urlPathMatching("/payment/v3/session/.*")));
	}

	@Test
	@DisplayName("스위치를_끄면_이전처럼_묻지_않고_푼다")
	void 만료_배치_스위치_끔() {
		startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));

		ReflectionTestUtils.setField(holdExpiryBatch, "point3Check", false);
		try {
			assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		} finally {
			ReflectionTestUtils.setField(holdExpiryBatch, "point3Check", true);
		}
		assertThat(held()).isZero();
		assertThat(paymentStatus()).containsExactly("CREATED");
		POINT3.verify(0, getRequestedFor(urlPathMatching("/payment/v3/session/.*")));
	}

	@Test
	@DisplayName("만료_후_늦게_온_confirm_도_구매자가_확정했고_회수_전이면_승인한다")
	void 늦은_confirm_committed() {
		String orderToken = startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(sold()).isEqualTo(3);
		assertThat(held()).isZero();
	}

	@Test
	@DisplayName("만료_배치가_먼저_넘긴_건에_confirm_이_와도_한_번만_확정된다")
	void 배치_구조_뒤_confirm() {
		String orderToken = startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		holdExpiryBatch.expireOnce();
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");

		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		PaymentResultResponse result = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 이미 CAPTURE_PENDING 이라 confirm 은 승인을 부르지 않고 배치에 맡긴다 (D-075)
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));

		agePending();
		assertThat(reconcileBatch.reconcileOnce()).isEqualTo(1);
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(sold()).isEqualTo(3);
		assertThat(held()).isZero();
	}

	// ---------------------------------------------------------------- 만료 조회 순환 · 알림 (D-079)

	@Test
	@DisplayName("만료_배치는_물어본_건을_줄_뒤로_보내_결론이_안_나는_건도_돌아가며_묻는다")
	void 만료_조회_순환() {
		startPayment();
		fixture.buyerWithAddress("kakao-payer2", "이도윤");
		SessionUser secondBuyer = new SessionUser("kakao-payer2", "결제자2");
		String secondToken = orderService.place(secondBuyer, order(2)).sessionToken();
		stubCreateSession(SESSION_ID_2);
		paymentService.pay(secondBuyer, secondToken);
		expireHolds();
		List<LocalDateTime> updatedBefore = updatedAts();

		// 5xx 면 한 건만 묻고 회차를 멈춘다. id 순이면 매 회차 첫 번째 세션만 물었다
		POINT3.stubFor(get(urlPathMatching("/payment/v3/session/.*")).willReturn(json(503, "{}")));

		holdExpiryBatch.expireOnce();
		holdExpiryBatch.expireOnce();
		holdExpiryBatch.expireOnce();

		POINT3.verify(2, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));
		POINT3.verify(1, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID_2)));
		assertThat(holdCheckMisses()).containsExactly(2, 1);
		assertThat(held()).isEqualTo(5);
		// 나중에 CAPTURE_PENDING 이 되면 멈춤 알림이 updated_at 으로 멈춘 시간을 잰다
		assertThat(updatedAts()).isEqualTo(updatedBefore);
	}

	@Test
	@DisplayName("만료_조회가_기준_횟수만큼_결론이_안_나면_한_번만_알린다")
	void 만료_조회_알림() {
		startPayment();
		expireHolds();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"something_new"}""".formatted(SESSION_ID))));

		RecordingAlertSender sender = new RecordingAlertSender();
		Object original = ReflectionTestUtils.getField(alerter, "alertSender");
		ReflectionTestUtils.setField(alerter, "alertSender", sender);
		ReflectionTestUtils.setField(holdExpiryBatch, "missAlertThreshold", 2);
		try {
			holdExpiryBatch.expireOnce();
			assertThat(sender.sent).isEmpty();

			holdExpiryBatch.expireOnce();
			assertThat(sender.sent).hasSize(1);
			assertThat(sender.sent.get(0).level()).isEqualTo(AlertLevel.WARN);
			assertThat(sender.sent.get(0).text()).contains("2번", SESSION_ID, "unknown");

			// 세션마다 한 번이다. 매분 울리면 채널이 묻힌다
			holdExpiryBatch.expireOnce();
			assertThat(sender.sent).hasSize(1);
		} finally {
			ReflectionTestUtils.setField(alerter, "alertSender", original);
			ReflectionTestUtils.setField(holdExpiryBatch, "missAlertThreshold", 5);
		}
		// 모르는 동안은 풀지 않는다
		assertThat(held()).isEqualTo(3);
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("만료_조회_알림이_실패하면_다음_회차에_다시_보낸다")
	void 만료_조회_알림_재시도() {
		startPayment();
		expireHolds();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(403, "{}")));

		RecordingAlertSender sender = new RecordingAlertSender();
		sender.succeed = false;
		Object original = ReflectionTestUtils.getField(alerter, "alertSender");
		ReflectionTestUtils.setField(alerter, "alertSender", sender);
		ReflectionTestUtils.setField(holdExpiryBatch, "missAlertThreshold", 1);
		try {
			holdExpiryBatch.expireOnce();
			assertThat(sender.sent).isEmpty();

			sender.succeed = true;
			holdExpiryBatch.expireOnce();
			assertThat(sender.sent).hasSize(1);
			assertThat(sender.sent.get(0).text()).contains("조회 거부 (403)");
		} finally {
			ReflectionTestUtils.setField(alerter, "alertSender", original);
			ReflectionTestUtils.setField(holdExpiryBatch, "missAlertThreshold", 5);
		}
	}

	@Test
	@DisplayName("결론이_난_조회는_횟수를_올리지_않는다")
	void 만료_조회_결론() {
		startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));

		holdExpiryBatch.expireOnce();

		assertThat(holdCheckMisses()).containsExactly(0);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
	}

	// ---------------------------------------------------------------- 선제 조회 (D-091)

	@Test
	@DisplayName("결과값이_안_와도_구매자가_확정했으면_선제_조회가_같은_회차에_승인한다")
	void 선제_조회_committed() {
		startPayment();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		// 홀드는 아직 살아 있다. 만료까지 기다리지 않는다
		assertThat(reconcileBatch.sessionCheckOnce()).isEqualTo(1);

		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(groupStatus()).containsExactly("PAID");
		assertThat(held()).isZero();
		assertThat(sold()).isEqualTo(3);
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("이미_captured_면_선제_조회는_승인을_다시_부르지_않고_확정만_한다")
	void 선제_조회_captured() {
		startPayment();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		assertThat(reconcileBatch.sessionCheckOnce()).isEqualTo(1);

		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(sold()).isEqualTo(3);
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("processing_이면_선제_조회는_CAPTURE_PENDING_으로_넘기고_대사에_맡긴다")
	void 선제_조회_processing() {
		startPayment();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"processing"}""".formatted(SESSION_ID));

		assertThat(reconcileBatch.sessionCheckOnce()).isEqualTo(1);

		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(holdStatus()).containsExactly("HELD");
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	@Test
	@DisplayName("세션을_붙인_지_30초가_안_됐으면_묻지_않는다")
	void 선제_조회_30초_전() {
		startPayment();

		// 정상이면 이 사이에 confirm 이 온다
		assertThat(sessionCheckDueAt()).isAfter(LocalDateTime.now(JpaAuditingConfig.KST).plusSeconds(20));
		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		POINT3.verify(0, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));
	}

	@Test
	@DisplayName("confirm_이_먼저_처리한_결제는_선제_조회가_묻지_않는다")
	void 선제_조회_confirm_뒤() {
		String orderToken = startPayment();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		dueNow();

		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		POINT3.verify(0, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));
	}

	@Test
	@DisplayName("구매자가_아직_확정하지_않았으면_아무것도_바꾸지_않고_간격을_늘려_다시_묻는다")
	void 선제_조회_간격() {
		startPayment();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));

		dueNow();
		LocalDateTime before = LocalDateTime.now(JpaAuditingConfig.KST);
		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		assertThat(sessionChecks()).isEqualTo(1);
		assertThat(Duration.between(before, sessionCheckDueAt())).isBetween(
				Duration.ofSeconds(55), Duration.ofSeconds(65));

		// 바로 다시 돌려도 차례가 아니다
		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		POINT3.verify(1, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));

		dueNow();
		before = LocalDateTime.now(JpaAuditingConfig.KST);
		reconcileBatch.sessionCheckOnce();
		assertThat(sessionChecks()).isEqualTo(2);
		assertThat(Duration.between(before, sessionCheckDueAt())).isBetween(
				Duration.ofSeconds(115), Duration.ofSeconds(125));

		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(held()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"failed", "expired"})
	@DisplayName("실패·만료로_결론이_나면_더_묻지_않고_홀드는_건드리지_않는다")
	void 선제_조회_결론(String status) {
		startPayment();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"%s"}""".formatted(SESSION_ID, status));

		assertThat(reconcileBatch.sessionCheckOnce()).isZero();

		assertThat(sessionCheckDueAt()).isNull();
		// 구매자가 창을 닫았다가 홀드 시간 안에 다시 결제할 수 있다. 회수는 만료 배치가 한다
		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(holdStatus()).containsExactly("HELD");
	}

	@Test
	@DisplayName("세션이_없으면_더_묻지_않는다")
	void 선제_조회_404() {
		startPayment();
		dueNow();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(404, "{}")));

		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		assertThat(sessionCheckDueAt()).isNull();
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("point3_가_5xx_면_아무것도_바꾸지_않고_다음_간격에_다시_묻는다")
	void 선제_조회_5xx() {
		startPayment();
		dueNow();
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(500, "{}")));

		assertThat(reconcileBatch.sessionCheckOnce()).isZero();

		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(sessionCheckDueAt()).isNotNull();
	}

	@Test
	@DisplayName("상한_횟수에_닿으면_더_묻지_않는다")
	void 선제_조회_상한() {
		startPayment();
		jdbcTemplate.update("UPDATE payment SET session_checks = ?",
				store.moeum.moeum.payment.domain.SessionCheckSchedule.MAX_CHECKS - 1);
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));

		reconcileBatch.sessionCheckOnce();

		assertThat(sessionCheckDueAt()).isNull();
	}

	@Test
	@DisplayName("만료_배치가_홀드를_걷은_묶음은_선제_조회가_묻지_않는다")
	void 선제_조회_회수된_묶음() {
		startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));
		assertThat(holdExpiryBatch.expireOnce()).isEqualTo(1);
		POINT3.resetRequests();
		dueNow();

		assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		POINT3.verify(0, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));
	}

	@Test
	@DisplayName("홀드_만료_뒤_회수_전이면_선제_조회도_구매자_확정을_살린다")
	void 선제_조회_만료_회수_전() {
		startPayment();
		expireHolds();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));

		// D-063 의 늦은 confirm 과 같은 기준이다. 재고가 아직 남에게 안 넘어갔다
		assertThat(reconcileBatch.sessionCheckOnce()).isEqualTo(1);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(sold()).isEqualTo(3);

		// 뒤이어 도는 만료 배치는 확정된 홀드를 건드리지 않는다
		assertThat(holdExpiryBatch.expireOnce()).isZero();
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("창을_닫고_다시_결제해_세션이_바뀌면_횟수를_처음부터_세고_옛_세션의_확정으로는_넘기지_않는다")
	void 선제_조회_재결제() {
		startPayment();
		Long paymentId = jdbcTemplate.queryForObject("SELECT id FROM payment", Long.class);
		jdbcTemplate.update("UPDATE payment SET session_checks = 7");
		dueNow();

		stubCreateSession(SESSION_ID_2);
		paymentService.pay(buyer(), sessionToken);

		assertThat(paymentSessionId()).containsExactly(SESSION_ID_2);
		assertThat(sessionChecks()).isZero();
		assertThat(sessionCheckDueAt()).isAfter(LocalDateTime.now(JpaAuditingConfig.KST).plusSeconds(20));

		// 옛 세션을 물어본 사이에 바뀐 경우다. 옛 세션은 아무도 승인하지 않아 출금되지 않는다
		assertThat(paymentWriter.rescueOnSessionCheck(paymentId, SESSION_ID)).isFalse();
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("스위치를_끄면_선제_조회를_하지_않는다")
	void 선제_조회_스위치_끔() {
		startPayment();
		dueNow();
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));
		ReflectionTestUtils.setField(reconcileBatch, "sessionCheck", false);
		try {
			assertThat(reconcileBatch.sessionCheckOnce()).isZero();
		} finally {
			ReflectionTestUtils.setField(reconcileBatch, "sessionCheck", true);
		}
		assertThat(paymentStatus()).containsExactly("CREATED");
		POINT3.verify(0, getRequestedFor(urlPathEqualTo("/payment/v3/session/" + SESSION_ID)));
	}

	/** 선제 조회 차례가 되게 만든다 */
	private void dueNow() {
		jdbcTemplate.update("UPDATE payment SET session_check_due_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND)"
				+ " WHERE session_check_due_at IS NOT NULL");
	}

	private LocalDateTime sessionCheckDueAt() {
		return jdbcTemplate.queryForObject("SELECT session_check_due_at FROM payment", LocalDateTime.class);
	}

	private int sessionChecks() {
		return jdbcTemplate.queryForObject("SELECT session_checks FROM payment", Integer.class);
	}

	private List<Integer> holdCheckMisses() {
		return jdbcTemplate.queryForList("SELECT hold_check_misses FROM payment ORDER BY id", Integer.class);
	}

	// ---------------------------------------------------------------- 승인은 한 곳에서만 부른다 (D-075)

	@Test
	@DisplayName("승인_결과를_모르는_건에_confirm_이_다시_와도_승인을_또_부르지_않는다")
	void 진행중_재confirm() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		PaymentResultResponse retry = paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 다시 부르면 앞선 호출과 겹칠 수 있다. 확정은 배치가 한다
		assertThat(retry.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
	}

	@Test
	@DisplayName("confirm_이_동시에_두_번_와도_승인은_한_번만_부른다")
	void 동시_confirm() throws Exception {
		String orderToken = startPayment();
		// 승인이 느리게 돌아오게 해 두 요청이 확실히 겹치게 한다
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID)).withFixedDelay(200)));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Callable<PaymentResultResponse.Status> confirm = () -> {
				start.await();
				return paymentService.confirm(buyer(), orderToken, SESSION_ID, null).status();
			};
			Future<PaymentResultResponse.Status> a = pool.submit(confirm);
			Future<PaymentResultResponse.Status> b = pool.submit(confirm);
			start.countDown();

			assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(PaymentResultResponse.Status.PAID, PaymentResultResponse.Status.PENDING);
		} finally {
			pool.shutdownNow();
		}
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(sold()).isEqualTo(3);
	}

	@Test
	@DisplayName("실패로_내려간_결제에_승인_성공이_오면_확정하지_않고_사람에게_알린다")
	void 실패_뒤_승인_성공() throws Exception {
		String orderToken = startPayment();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(200, """
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID)).withFixedDelay(300)));

		RecordingAlertSender sender = new RecordingAlertSender();
		Object original = ReflectionTestUtils.getField(alerter, "alertSender");
		ReflectionTestUtils.setField(alerter, "alertSender", sender);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<PaymentResultResponse> result = pool.submit(
					() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, null));

			// 승인 응답을 기다리는 사이에 다른 경로가 실패로 내렸다고 친다
			awaitPaymentStatus("CAPTURE_PENDING");
			jdbcTemplate.update("UPDATE payment SET status = 'FAILED'");

			// 돈은 나갔다. 실패라고 답하면 구매자가 다시 결제한다
			assertThat(result.get(5, TimeUnit.SECONDS).status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		} finally {
			pool.shutdownNow();
			ReflectionTestUtils.setField(alerter, "alertSender", original);
		}

		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(sold()).isZero();
		assertThat(sender.sent).singleElement()
				.satisfies(s -> assertThat(s.level()).isEqualTo(AlertLevel.CRITICAL))
				.satisfies(s -> assertThat(s.text()).contains("point3 에서 승인됐다", SESSION_ID));
	}

	@Test
	@DisplayName("확정은_FAILED_에서_CAPTURED_로_올리지_않는다")
	void 실패_결제는_확정하지_않는다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		jdbcTemplate.update("UPDATE payment SET status = 'FAILED'");
		Long paymentId = jdbcTemplate.queryForObject("SELECT id FROM payment", Long.class);

		assertThatThrownBy(() -> paymentWriter.finalizeCapture(paymentId, PaymentActor.BATCH))
				.isInstanceOf(CapturedAfterFailureException.class);
		assertThat(paymentStatus()).containsExactly("FAILED");
		assertThat(sold()).isZero();
	}

	@Test
	@DisplayName("이미_회수된_홀드로는_구매자가_확정했어도_승인하지_않는다")
	void 회수된_뒤_confirm() {
		String orderToken = startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));
		holdExpiryBatch.expireOnce();
		assertThat(holdStatus()).containsExactly("RELEASED");

		// 회수 뒤에 구매자가 확정했다 — 그 재고는 이미 남의 것일 수 있다
		stubGetSession("""
				{"id":"%s","status":"committed"}""".formatted(SESSION_ID));

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(sold()).isZero();
	}

	@Test
	@DisplayName("만료_후_confirm_인데_구매자가_확정하지_않았으면_거절한다")
	void 늦은_confirm_initiated() {
		String orderToken = startPayment();
		expireHolds();
		stubGetSession("""
				{"id":"%s","status":"initiated"}""".formatted(SESSION_ID));

		assertThatThrownBy(() -> paymentService.confirm(buyer(), orderToken, SESSION_ID, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.HOLD_EXPIRED);
		POINT3.verify(0, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
	}

	// ---------------------------------------------------------------- 재결제 · 조회

	@Test
	@DisplayName("실패한_뒤에는_같은_행을_재사용해_다시_결제할_수_있다")
	void 재결제() {
		String orderToken = startPayment();
		stubCapture(400, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(paymentStatus()).containsExactly("FAILED");

		// 홀드가 풀렸으니 주문부터 다시 만든다
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(10, null);
		sessionToken = orderService.place(buyer(), order(2)).sessionToken();
		stubCreateSession();

		PaySessionResponse retry = paymentService.pay(buyer(), sessionToken);

		assertThat(retry.sessionId()).isEqualTo(SESSION_ID);
		assertThat(paymentStatus()).containsExactly("CREATED");
	}

	@Test
	@DisplayName("결제창을_닫고_다시_결제하면_같은_orderToken_으로_승인된다")
	void 창_닫고_재결제() {
		String orderToken = startPayment();

		// 창을 닫고 상세로 나갔다. 이어서 결제할 세션 토큰이 실려 있어야 한다
		PaymentResultResponse detail = paymentService.status(buyer(), orderToken);
		assertThat(detail.pendingReason()).isEqualTo(PaymentResultResponse.PendingReason.AWAITING_PAYMENT);
		assertThat(detail.sessionToken()).isEqualTo(sessionToken);

		PaySessionResponse retry = paymentService.pay(buyer(), detail.sessionToken());

		// 새 토큰을 내려주면 DB 에 없는 값이라 승인·조회가 주문을 못 찾는다
		assertThat(retry.orderToken()).isEqualTo(orderToken);
		POINT3.verify(2, postRequestedFor(urlPathEqualTo("/payment/v3/session")));

		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		PaymentResultResponse result = paymentService.confirm(buyer(), retry.orderToken(), retry.sessionId(), null);

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
		assertThat(paymentStatus()).containsExactly("CAPTURED");
		// 결제가 끝나면 이어하기 토큰을 내려주지 않는다
		assertThat(paymentService.status(buyer(), orderToken).sessionToken()).isNull();
	}

	@Test
	@DisplayName("결과_불명인_주문에_새_세션을_열지_않는다")
	void 진행중이면_새_세션을_막는다() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 여기서 새 세션을 열어주면 이중 결제가 된다
		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
	}

	/**
	 * 다른 탭에서 결제하기를 또 눌렀는데, 그 요청이 point3 세션을 만드는 사이에
	 * 첫 탭이 승인을 시작한 경우 (D-076). 세션 생성은 트랜잭션 밖이라 그 틈을 writer 로 쪼개 재현한다.
	 */
	@Test
	@DisplayName("세션을_만드는_사이_승인이_시작됐으면_새_세션을_붙이지_않는다")
	void 세션_생성_중_승인_시작() {
		String orderToken = startPayment();

		// 탭 B: 준비는 통과했다 (아직 CREATED)
		PaymentWriter.Prepared prepared = paymentWriter.prepareFirst(buyer().kakaoId(), sessionToken);

		// 탭 A: 승인을 불렀는데 결과를 모른다
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");

		// 탭 B: point3 세션을 받아 와 붙이려 한다
		assertThatThrownBy(() -> paymentWriter.attachSession(prepared, otherSession(), "ord_other"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);

		// 출금 중인 건의 기록이 그대로여야 대사 배치가 이전 세션으로 확정한다
		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(paymentSessionId()).containsExactly(SESSION_ID);
		assertThat(held()).isEqualTo(3);
	}

	@Test
	@DisplayName("세션을_만드는_사이_결제가_끝났으면_새_세션을_붙이지_않는다")
	void 세션_생성_중_결제_완료() {
		String orderToken = startPayment();
		PaymentWriter.Prepared prepared = paymentWriter.prepareFirst(buyer().kakaoId(), sessionToken);

		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(SESSION_ID));
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		// 전에는 묶음 상태 검사(IllegalStateException)에 걸려 500 이었다
		assertThatThrownBy(() -> paymentWriter.attachSession(prepared, otherSession(), "ord_other"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);

		assertThat(paymentStatus()).containsExactly("CAPTURED");
		assertThat(paymentSessionId()).containsExactly(SESSION_ID);
	}

	/**
	 * 재결제에서 결제하기를 두 번 누른 경우 (D-077). 둘 다 prepare 를 통과하고 각자 세션을 받아 온다.
	 * 나중 것이 덮으면 먼저 받은 응답으로 연 결제창이 SESSION_MISMATCH 로 거절된다.
	 */
	@Test
	@DisplayName("세션을_먼저_붙인_요청만_남고_늦은_요청은_막는다")
	void 결제하기_두_번() {
		startPayment();
		PaymentWriter.Prepared first = paymentWriter.prepareFirst(buyer().kakaoId(), sessionToken);
		PaymentWriter.Prepared second = paymentWriter.prepareFirst(buyer().kakaoId(), sessionToken);

		paymentWriter.attachSession(first, otherSession(), "ord_first");

		assertThatThrownBy(() -> paymentWriter.attachSession(second, new Point3Session(
				"pymt_sess-019f0000-0000-7000-9000-000000000002", null, 99000, 90000, 9000, 0, "KRW"), "ord_second"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
		assertThat(paymentSessionId()).containsExactly(otherSession().id());
	}

	/**
	 * 첫 결제하기가 동시에 여러 번 온 경우 (D-077). 전에는 payment 행을 같이 만들다 유니크 키에 걸린 쪽이 500 이었다.
	 */
	@Test
	@DisplayName("첫_결제하기가_동시에_와도_하나만_통과하고_나머지는_진행_중으로_답한다")
	void 동시_첫_결제하기() throws Exception {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID)).withFixedDelay(500)));

		int threads = 6;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<String> outcomes = new java.util.ArrayList<>();
		try {
			Callable<String> pay = () -> {
				start.await();
				try {
					paymentService.pay(buyer(), sessionToken);
					return "OK";
				} catch (BusinessException e) {
					return e.errorCode().name();
				}
			};
			List<Future<String>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(pay));
			}
			start.countDown();
			for (Future<String> future : futures) {
				outcomes.add(future.get(10, TimeUnit.SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		// 500(DataIntegrityViolation)이 섞이면 future.get 이 ExecutionException 으로 터진다
		assertThat(outcomes).containsOnlyOnce("OK");
		assertThat(outcomes).filteredOn(o -> !o.equals("OK")).containsOnly(ErrorCode.PAYMENT_IN_PROGRESS.name());
		assertThat(paymentStatus()).containsExactly("CREATED");
		assertThat(paymentSessionId()).containsExactly(SESSION_ID);
	}

	@Test
	@DisplayName("상태_조회는_부작용이_없다")
	void 상태_조회() {
		String orderToken = startPayment();
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, SESSION_ID, null);

		PaymentResultResponse first = paymentService.status(buyer(), orderToken);
		PaymentResultResponse second = paymentService.status(buyer(), orderToken);

		assertThat(first.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		assertThat(second.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		// 조회가 승인을 부르면 조회할 때마다 결제가 일어나는 API 가 된다
		POINT3.verify(1, postRequestedFor(urlPathEqualTo("/capture/v2/" + SESSION_ID)));
		assertThat(held()).isEqualTo(3);
	}

	// ---------------------------------------------------------------- 도우미

	private String startPayment() {
		stubCreateSession();
		return paymentService.pay(buyer(), sessionToken).orderToken();
	}

	private void stubCreateSession() {
		stubCreateSession(SESSION_ID);
	}

	private void stubCreateSession(String sessionId) {
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(sessionId))));
	}

	/** 승인 호출이 500 으로 끝나 CAPTURE_PENDING 에 남은 결제를 만든다. 결제 id 를 돌려준다 */
	private Long pendingPayment(SessionUser user, String checkoutToken, String sessionId) {
		stubCreateSession(sessionId);
		String orderToken = paymentService.pay(user, checkoutToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + sessionId))
				.willReturn(json(500, "{}")));
		paymentService.confirm(user, orderToken, sessionId, null);
		return jdbcTemplate.queryForObject(
				"SELECT id FROM payment WHERE session_id = ? AND status = 'CAPTURE_PENDING'",
				Long.class, sessionId);
	}

	private List<LocalDateTime> updatedAts() {
		return jdbcTemplate.queryForList(
				"SELECT updated_at FROM payment ORDER BY id", LocalDateTime.class);
	}

	private void stubCapture(int status, String body) {
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json(status, body)));
	}

	private void stubGetSession(String body) {
		POINT3.stubFor(get(urlPathEqualTo("/payment/v3/session/" + SESSION_ID))
				.willReturn(json(200, body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private Object originalAlertSender;

	/**
	 * 알림을 가로챈다. 401 · 403 알림은 인스턴스 메모리 쿨다운(30분)이 있어서
	 * 앞선 테스트가 울렸으면 막힌다 — 쿨다운도 새로 만든다.
	 */
	private RecordingAlertSender swapAlertSender() {
		RecordingAlertSender sender = new RecordingAlertSender();
		originalAlertSender = ReflectionTestUtils.getField(alerter, "alertSender");
		ReflectionTestUtils.setField(alerter, "alertSender", sender);
		ReflectionTestUtils.setField(alerter, "authCooldown",
				new AlertCooldown(Duration.ofMinutes(30), Clock.systemUTC()));
		return sender;
	}

	private void restoreAlertSender() {
		ReflectionTestUtils.setField(alerter, "alertSender", originalAlertSender);
	}

	/** 배치의 유예 시간(1분)을 지나게 만든다 */
	private void agePending() {
		jdbcTemplate.update("UPDATE payment SET updated_at = DATE_SUB(NOW(6), INTERVAL 5 MINUTE)");
	}

	private void expireHolds() {
		jdbcTemplate.update("UPDATE stock_hold SET expires_at = DATE_SUB(NOW(6), INTERVAL 1 MINUTE)");
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private static SessionUser other() {
		return new SessionUser("kakao-stranger", "남");
	}

	private OrderCreateRequest order(int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)));
	}

	/** 다른 스레드의 confirm 이 CAPTURE_PENDING 을 커밋할 때까지 기다린다 */
	/** 다른 트랜잭션이 행 하나를 FOR UPDATE 로 쥔 동안 action 을 돌린다 */
	private <T> T whileLocked(String lockSql, Object arg, Callable<T> action) throws Exception {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService other = Executors.newSingleThreadExecutor();
		Future<?> holding = other.submit(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					assertThat(jdbcTemplate.queryForList(lockSql, arg)).isNotEmpty();
					locked.countDown();
					try {
						release.await(20, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}));
		try {
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			return action.call();
		} finally {
			release.countDown();
			holding.get(10, TimeUnit.SECONDS);
			other.shutdown();
		}
	}

	private String payerId() {
		return jdbcTemplate.queryForObject(
				"SELECT payer_id FROM buyer WHERE kakao_id = ?", String.class, "kakao-payer");
	}

	private void awaitPaymentStatus(String status) throws InterruptedException {
		long until = System.currentTimeMillis() + 3000;
		while (!paymentStatus().contains(status)) {
			if (System.currentTimeMillis() > until) {
				throw new AssertionError("결제가 " + status + " 가 되지 않았다: " + paymentStatus());
			}
			Thread.sleep(10);
		}
	}

	private List<String> paymentStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM payment", String.class);
	}

	private List<String> paymentSessionId() {
		return jdbcTemplate.queryForList("SELECT session_id FROM payment", String.class);
	}

	/** 다른 탭이 새로 받아 온 point3 세션 */
	private static Point3Session otherSession() {
		return new Point3Session("pymt_sess-019f0000-0000-7000-9000-000000000001",
				null, 99000, 90000, 9000, 0, "KRW");
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}

	private List<String> holdStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM stock_hold", String.class);
	}

	private int held() {
		return jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}

	private int sold() {
		return jdbcTemplate.queryForObject(
				"SELECT sold FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
	}
}
