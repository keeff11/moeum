package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이탈 해제(POST /orders/{sessionToken}/release)가 다른 경로와 겹쳐도 재고가 한 번만 돌아간다 (D-088).
 *
 * 이탈 해제는 홀드를 잠그지 않고 읽었다. 그래서 다른 트랜잭션이 같은 홀드를 고치는 중이면
 * 커밋 전 상태(HELD)를 보고 자기도 풀었다 — 앞선 쪽이 커밋하면 그 위에 RELEASED 를 덮어쓰고
 * held 를 한 번 더 뺐다. 다른 구매자의 홀드가 같은 폼에 있으면 held >= qty 가드도 통과한다.
 *
 * 겹치는 순간은 판매 폼 행을 테스트가 쥐고 있는 것으로 만든다. 먼저 들어간 쪽은 홀드를 고친 뒤
 * 재고 행에서 멈추고, 그사이 이탈 해제가 들어온다.
 */
class ReleaseConcurrencyTest extends IntegrationTest {

	/** 같은 폼에 다른 구매자가 잡아 둔 수량. 이게 있어야 두 번째로 빼는 것이 가드에 걸리지 않는다 */
	private static final int OTHERS = 3;
	private static final int MINE = 2;

	@Autowired
	private OrderService orderService;

	@Autowired
	private HoldExpiryBatch batch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;
	private SessionUser me;
	private OrderGroupResponse mine;

	@BeforeEach
	void setUp() {
		fixture.clean();
		setup = fixture.saleForm(10, null);
		orderService.place(buyer("kakao-other"), order(OTHERS));
		me = buyer("kakao-me");
		mine = orderService.place(me, order(MINE));
		assertThat(formHeld()).isEqualTo(OTHERS + MINE);
	}

	@Test
	@DisplayName("이탈_해제가_동시에_두_번_와도_재고는_한_번만_돌아간다")
	void 동시에_두_번() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(3);
		FormLock lock = lockForm(pool);
		try {
			// 첫 번째는 홀드를 RELEASED 로 고친 뒤 재고 행에서 멈춘다
			Future<?> first = pool.submit(() -> orderService.release(me, mine.sessionToken()));
			awaitHoldLocked();

			// 두 번째는 첫 번째가 커밋하기 전에 들어온다
			Future<?> second = pool.submit(() -> orderService.release(me, mine.sessionToken()));
			Thread.sleep(500);

			lock.release();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		} finally {
			lock.release();
			pool.shutdownNow();
		}

		assertThat(formHeld()).isEqualTo(OTHERS);
		assertThat(optionHeld()).isEqualTo(OTHERS);
		assertThat(holdStatus()).containsExactly("RELEASED");
		assertThat(groupStatus()).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("만료_배치가_푸는_중에_이탈_해제가_와도_재고는_한_번만_돌아간다")
	void 만료_배치와_겹침() throws Exception {
		jdbcTemplate.update("""
				UPDATE stock_hold h
				  JOIN orders o ON o.id = h.order_id
				  JOIN order_group g ON g.id = o.order_group_id
				   SET h.expires_at = NOW(6) - INTERVAL 1 MINUTE
				 WHERE g.session_token = ?
				""", mine.sessionToken());

		ExecutorService pool = Executors.newFixedThreadPool(3);
		FormLock lock = lockForm(pool);
		try {
			// 배치가 내 홀드를 잠그고 RELEASED 로 고친 뒤 재고 행에서 멈춘다
			Future<Integer> expiring = pool.submit(batch::expireOnce);
			awaitHoldLocked();

			Future<?> leaving = pool.submit(() -> orderService.release(me, mine.sessionToken()));
			Thread.sleep(500);

			lock.release();
			assertThat(expiring.get(10, TimeUnit.SECONDS)).isEqualTo(1);
			leaving.get(10, TimeUnit.SECONDS);
		} finally {
			lock.release();
			pool.shutdownNow();
		}

		assertThat(formHeld()).isEqualTo(OTHERS);
		assertThat(optionHeld()).isEqualTo(OTHERS);
		assertThat(holdStatus()).containsExactly("RELEASED");
		assertThat(groupStatus()).isEqualTo("EXPIRED");
	}

	/**
	 * 결제 확정이 커밋되기 직전에 이탈 해제가 온 경우. 커밋 전 상태(HELD)를 보고 풀면
	 * 확정 위에 RELEASED · EXPIRED 를 덮어써, 돈을 받은 주문의 재고가 다른 사람에게 다시 팔린다.
	 */
	@Test
	@DisplayName("결제_확정과_겹친_이탈_해제는_판매_확정을_덮어쓰지_않는다")
	void 결제_확정과_겹침() throws Exception {
		openPayment("CAPTURE_PENDING");

		// finalizeCapture 와 같은 순서로 묶음을 잠그고 판매 확정한 채 커밋 직전에 멈춘다
		CountDownLatch finalizing = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> finalize = pool.submit(() -> new TransactionTemplate(transactionManager)
					.executeWithoutResult(status -> {
						jdbcTemplate.queryForList("SELECT id FROM order_group WHERE session_token = ? FOR UPDATE",
								mine.sessionToken());
						settle();
						finalizing.countDown();
						await(commit);
					}));
			assertThat(finalizing.await(5, TimeUnit.SECONDS)).isTrue();

			Future<?> leaving = pool.submit(() -> orderService.release(me, mine.sessionToken()));
			Thread.sleep(500);

			commit.countDown();
			finalize.get(10, TimeUnit.SECONDS);
			leaving.get(10, TimeUnit.SECONDS);
		} finally {
			commit.countDown();
			pool.shutdownNow();
		}

		assertThat(groupStatus()).isEqualTo("PAID");
		assertThat(holdStatus()).containsExactly("COMMITTED");
		assertThat(formHeld()).isEqualTo(OTHERS);
		assertThat(formSold()).isEqualTo(MINE);
	}

	/**
	 * 결제창을 연 묶음은 이탈 해제로 풀지 않는다. 구매자가 결제창에서 확정했는데 결과가 아직
	 * 안 닿았을 수 있다 — 그건 만료 배치가 point3 에 물어보고 정한다 (D-063).
	 */
	@Test
	@DisplayName("결제창을_연_묶음은_이탈_해제가_풀지_않고_만료_배치에_맡긴다")
	void 결제창을_연_묶음() {
		openPayment("CREATED");

		orderService.release(me, mine.sessionToken());

		assertThat(formHeld()).isEqualTo(OTHERS + MINE);
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(groupStatus()).isEqualTo("CREATED");
	}

	@Test
	@DisplayName("승인을_기다리는_묶음은_이탈_해제가_풀지_않는다")
	void 승인_대기_묶음() {
		openPayment("CAPTURE_PENDING");
		jdbcTemplate.update("UPDATE order_group SET status = 'PAY_PENDING' WHERE session_token = ?",
				mine.sessionToken());

		orderService.release(me, mine.sessionToken());

		assertThat(formHeld()).isEqualTo(OTHERS + MINE);
		assertThat(holdStatus()).containsExactly("HELD");
		assertThat(groupStatus()).isEqualTo("PAY_PENDING");
	}

	/** 판매 폼 행을 다른 트랜잭션에서 쥐고 있는다. 재고를 고치려는 쪽은 여기서 멈춘다 */
	private FormLock lockForm(ExecutorService pool) throws InterruptedException {
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbcTemplate.queryForList("SELECT id FROM sale_form WHERE id = ? FOR UPDATE", setup.saleFormId());
			locked.countDown();
			await(release);
		}));
		assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
		return new FormLock(release);
	}

	private record FormLock(CountDownLatch latch) {
		void release() {
			latch.countDown();
		}
	}

	/**
	 * 내 홀드 행을 누가 쥘 때까지 기다린다. 테스트 계정은 innodb_trx 를 못 봐서 NOWAIT 로 찔러 본다.
	 * 쥔 쪽은 홀드를 고친 뒤 재고 행에서 막혀 있다
	 */
	private void awaitHoldLocked() throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			try {
				jdbcTemplate.queryForList("""
						SELECT h.id FROM stock_hold h
						  JOIN orders o ON o.id = h.order_id
						  JOIN order_group g ON g.id = o.order_group_id
						 WHERE g.session_token = ?
						   FOR UPDATE OF h NOWAIT
						""", mine.sessionToken());
			} catch (DataAccessException held) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("아무도 홀드 행을 잡지 않았다");
	}

	private void openPayment(String status) {
		jdbcTemplate.update("""
						INSERT INTO payment (order_group_id, phase, session_id, amount, tax_free_amount, status)
						SELECT id, 'FIRST', ?, deposit1_total, 0, ? FROM order_group WHERE session_token = ?
						""",
				"pymt_" + System.nanoTime(), status, mine.sessionToken());
	}

	/** 결제 확정이 하는 쓰기를 SQL 로 한다: 홀드 판매 확정, 재고 held → sold, 묶음 PAID */
	private void settle() {
		jdbcTemplate.update("""
				UPDATE stock_hold h
				  JOIN orders o ON o.id = h.order_id
				  JOIN order_group g ON g.id = o.order_group_id
				   SET h.status = 'COMMITTED'
				 WHERE g.session_token = ?
				""", mine.sessionToken());
		jdbcTemplate.update("UPDATE sale_form SET held = held - ?, sold = sold + ? WHERE id = ?",
				MINE, MINE, setup.saleFormId());
		jdbcTemplate.update("UPDATE product_option SET held = held - ?, sold = sold + ? WHERE id = ?",
				MINE, MINE, setup.optionId());
		jdbcTemplate.update("UPDATE order_group SET status = 'PAID' WHERE session_token = ?", mine.sessionToken());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(20, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private int formHeld() {
		return jdbcTemplate.queryForObject("SELECT held FROM sale_form WHERE id = ?", Integer.class,
				setup.saleFormId());
	}

	private int formSold() {
		return jdbcTemplate.queryForObject("SELECT sold FROM sale_form WHERE id = ?", Integer.class,
				setup.saleFormId());
	}

	private int optionHeld() {
		return jdbcTemplate.queryForObject("SELECT held FROM product_option WHERE id = ?", Integer.class,
				setup.optionId());
	}

	private String groupStatus() {
		return jdbcTemplate.queryForObject("SELECT status FROM order_group WHERE session_token = ?", String.class,
				mine.sessionToken());
	}

	private List<String> holdStatus() {
		return jdbcTemplate.queryForList("""
						SELECT h.status FROM stock_hold h
						  JOIN orders o ON o.id = h.order_id
						  JOIN order_group g ON g.id = o.order_group_id
						 WHERE g.session_token = ?
						""", String.class, mine.sessionToken());
	}

	private SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private OrderCreateRequest order(int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), qty)));
	}
}
