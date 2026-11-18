package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 같은 구매자가 같은 폼을 다시 담으면 이전 홀드를 풀고 새로 잡는다 (D-073).
 *
 * 1인 구매 제한이 없는 기획이라 막을 수 있는 것은 "한 사람이 같은 폼에 홀드를 여러 벌" 뿐이다.
 * 뒤로 가서 수량을 바꾸는 정상 사용자가 유령 홀드를 남기지 않게 하는 것이 주목적이다.
 */
class CheckoutReplaceTest extends IntegrationTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	@Test
	@DisplayName("같은_폼을_다른_수량으로_다시_담으면_이전_홀드를_풀고_새로_잡는다")
	void 수량을_바꾸면_교체한다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-change");

		OrderGroupResponse first = orderService.place(user, order(setup.optionId(), 3));
		OrderGroupResponse second = orderService.place(user, order(setup.optionId(), 5));

		assertThat(second.sessionToken()).isNotEqualTo(first.sessionToken());
		// 3 + 5 = 8 이 아니다. 이전 3개는 바로 재고로 돌아갔다
		assertThat(formHeld(setup)).isEqualTo(5);
		assertThat(optionHeld(setup.optionId())).isEqualTo(5);
		assertThat(groupStatus(first.sessionToken())).isEqualTo("EXPIRED");
		assertThat(groupStatus(second.sessionToken())).isEqualTo("CREATED");
	}

	@Test
	@DisplayName("같은_폼의_다른_옵션으로_바꿔도_교체한다")
	void 옵션을_바꿔도_교체한다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-option");

		orderService.place(user, order(setup.optionId(), 2));
		orderService.place(user, order(setup.secondOptionId(), 2));

		assertThat(formHeld(setup)).isEqualTo(2);
		assertThat(optionHeld(setup.optionId())).isZero();
		assertThat(optionHeld(setup.secondOptionId())).isEqualTo(2);
	}

	@Test
	@DisplayName("남은_재고가_내_홀드뿐이어도_수량을_늘릴_수_있다")
	void 내가_잡은_재고는_다시_쓸_수_있다() {
		OrderFixture.Setup setup = fixture.saleForm(3, null);
		SessionUser user = buyer("kakao-grow");
		orderService.place(user, order(setup.optionId(), 2));

		// 남은 재고는 1개다. 내가 잡은 2개를 같은 트랜잭션에서 풀고 다시 잡으므로 3개가 된다
		orderService.place(user, order(setup.optionId(), 3));

		assertThat(formHeld(setup)).isEqualTo(3);
	}

	@Test
	@DisplayName("새로_잡다가_품절이면_이전_홀드는_그대로_남는다")
	void 품절이면_원래_자리를_잃지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(3, null);
		SessionUser user = buyer("kakao-keep");
		OrderGroupResponse mine = orderService.place(user, order(setup.optionId(), 2));
		orderService.place(buyer("kakao-other"), order(setup.optionId(), 1));

		assertThatThrownBy(() -> orderService.place(user, order(setup.optionId(), 3)))
				.isInstanceOf(OutOfStockException.class);

		// 푼 것이 같이 롤백됐다. 수량을 늘리려다 원래 잡은 2개까지 남에게 넘어가면 안 된다
		assertThat(formHeld(setup)).isEqualTo(3);
		assertThat(groupStatus(mine.sessionToken())).isEqualTo("CREATED");
		assertThat(holdStatus(mine.sessionToken())).containsExactly("HELD");
	}

	@Test
	@DisplayName("다른_폼을_담으면_이전_홀드를_건드리지_않는다")
	void 다른_폼은_건드리지_않는다() {
		OrderFixture.Setup formA = fixture.saleForm(10, null);
		OrderFixture.Setup formB = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-two-forms");

		OrderGroupResponse a = orderService.place(user, order(formA.optionId(), 2));
		orderService.place(user, order(formB.optionId(), 4));

		assertThat(formHeld(formA)).isEqualTo(2);
		assertThat(formHeld(formB)).isEqualTo(4);
		assertThat(groupStatus(a.sessionToken())).isEqualTo("CREATED");
	}

	@Test
	@DisplayName("겹치는_폼이_있으면_이전_묶음을_통째로_푼다")
	void 묶음째_푼다() {
		OrderFixture.Setup formA = fixture.saleForm(10, null);
		OrderFixture.Setup formB = fixture.saleFormOfSameSeller(formA, 10);
		SessionUser user = buyer("kakao-bundle");

		OrderGroupResponse cart = orderService.place(user, new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(formA.optionId(), 1),
				new OrderCreateRequest.Item(formB.optionId(), 1))));
		orderService.place(user, order(formA.optionId(), 2));

		// 배송비가 묶음당 1회라 B 만 남겨 둘 수 없다
		assertThat(formHeld(formA)).isEqualTo(2);
		assertThat(formHeld(formB)).isZero();
		assertThat(groupStatus(cart.sessionToken())).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("다른_구매자의_홀드는_풀지_않는다")
	void 남의_홀드는_풀지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		OrderGroupResponse theirs = orderService.place(buyer("kakao-a"), order(setup.optionId(), 2));

		orderService.place(buyer("kakao-b"), order(setup.optionId(), 3));

		assertThat(formHeld(setup)).isEqualTo(5);
		assertThat(groupStatus(theirs.sessionToken())).isEqualTo("CREATED");
	}

	@Test
	@DisplayName("결제창을_연_묶음이_있으면_풀지_않고_막는다")
	void 결제_진행_중이면_막는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-paying");
		OrderGroupResponse paying = orderService.place(user, order(setup.optionId(), 2));
		// /pay 가 payment 행을 만든 직후다. 묶음은 세션이 붙기 전까지 CREATED 그대로다
		openPayment(paying.sessionToken());

		assertThatThrownBy(() -> orderService.place(user, order(setup.optionId(), 3)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.CHECKOUT_PAYMENT_IN_PROGRESS);

		// point3 에서 승인이 올 수 있는 재고다. 풀면 승인이 와도 확정할 것이 없다
		assertThat(formHeld(setup)).isEqualTo(2);
		assertThat(holdStatus(paying.sessionToken())).containsExactly("HELD");
	}

	@Test
	@DisplayName("결제창까지_간_PAY_PENDING_묶음도_막는다")
	void 결제_대기_묶음도_막는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-pending");
		OrderGroupResponse paying = orderService.place(user, order(setup.optionId(), 2));
		openPayment(paying.sessionToken());
		jdbcTemplate.update("UPDATE order_group SET status = 'PAY_PENDING' WHERE session_token = ?",
				paying.sessionToken());

		assertThatThrownBy(() -> orderService.place(user, order(setup.optionId(), 1)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.CHECKOUT_PAYMENT_IN_PROGRESS);
		assertThat(formHeld(setup)).isEqualTo(2);
	}

	/**
	 * 담기 버튼을 여러 번 누른 경우 (D-077). 이어받기와 교체는 커밋된 묶음만 보므로, 구매자 단위로
	 * 줄을 세우기 전에는 동시에 온 요청이 서로를 못 보고 각자 홀드를 잡았다.
	 */
	@Test
	@DisplayName("같은_담기가_동시에_여러_번_와도_홀드는_한_벌이고_같은_세션을_돌려준다")
	void 동시에_같은_담기() throws Exception {
		OrderFixture.Setup setup = fixture.saleForm(100, null);
		fixture.buyerWithAddress("kakao-double", "구매자");
		SessionUser user = buyer("kakao-double");
		int threads = 8;

		List<OrderGroupResponse> responses = concurrently(threads,
				() -> orderService.place(user, order(setup.optionId(), 3)));

		assertThat(responses).hasSize(threads);
		assertThat(responses).extracting(OrderGroupResponse::sessionToken).containsOnly(responses.get(0).sessionToken());
		assertThat(formHeld(setup)).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_group", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("동시에_다른_수량으로_담으면_하나만_남는다")
	void 동시에_다른_수량() throws Exception {
		OrderFixture.Setup setup = fixture.saleForm(100, null);
		fixture.buyerWithAddress("kakao-race", "구매자");
		SessionUser user = buyer("kakao-race");
		AtomicInteger qty = new AtomicInteger();

		concurrently(6, () -> orderService.place(user, order(setup.optionId(), qty.incrementAndGet())));

		// 마지막에 커밋된 것 하나만 HELD 로 남고 나머지는 교체돼 풀렸다
		Integer live = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM stock_hold WHERE status = 'HELD'", Integer.class);
		Integer liveQty = jdbcTemplate.queryForObject(
				"SELECT COALESCE(SUM(qty), 0) FROM stock_hold WHERE status = 'HELD'", Integer.class);
		assertThat(live).isEqualTo(1);
		assertThat(formHeld(setup)).isEqualTo(liveQty);
	}

	// ---------------------------------------------------------------- 도우미

	/** 준비된 스레드를 한꺼번에 출발시킨다. 하나라도 예외면 그대로 던진다 */
	private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await();
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private void openPayment(String sessionToken) {
		jdbcTemplate.update("""
						INSERT INTO payment (order_group_id, phase, session_id, amount, tax_free_amount, status)
						SELECT id, 'FIRST', ?, deposit1_total, 0, 'CREATED' FROM order_group WHERE session_token = ?
						""",
				"pymt_" + System.nanoTime(), sessionToken);
	}

	private int formHeld(OrderFixture.Setup setup) {
		Integer value = jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
		return value == null ? 0 : value;
	}

	private int optionHeld(Long optionId) {
		Integer value = jdbcTemplate.queryForObject(
				"SELECT held FROM product_option WHERE id = ?", Integer.class, optionId);
		return value == null ? 0 : value;
	}

	private String groupStatus(String sessionToken) {
		return jdbcTemplate.queryForObject(
				"SELECT status FROM order_group WHERE session_token = ?", String.class, sessionToken);
	}

	private List<String> holdStatus(String sessionToken) {
		return jdbcTemplate.queryForList("""
						SELECT h.status FROM stock_hold h
						  JOIN orders o ON o.id = h.order_id
						  JOIN order_group g ON g.id = o.order_group_id
						 WHERE g.session_token = ?
						""", String.class, sessionToken);
	}

	private SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}
}
