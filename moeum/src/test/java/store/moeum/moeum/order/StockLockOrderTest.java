package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.order.dto.OrderCreateRequest;
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
 * 홀드를 돌며 재고 행을 고치는 경로는 판매 폼 id 순으로 고친다 (D-086).
 *
 * 주문 생성은 판매 폼을 id 오름차순으로 잠근다. 홀드 순서(홀드 id)대로 재고를 고치는 경로가 있으면,
 * 홀드 id 와 판매 폼 id 의 순서가 엇갈릴 때 주문 생성과 반대 순서로 잠가 교착이 난다.
 */
class StockLockOrderTest extends IntegrationTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private HoldExpiryBatch batch;

	@Autowired
	private StockHoldRepository stockHoldRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup formA;
	private OrderFixture.Setup formB;

	@BeforeEach
	void setUp() {
		fixture.clean();
		formA = fixture.saleForm(10, null);
		formB = fixture.saleForm(10, null);
		assertThat(formA.saleFormId()).isLessThan(formB.saleFormId());

		// 홀드 id 순서를 판매 폼 순서와 엇갈리게 만든다: B 의 홀드가 먼저 생긴다
		orderService.place(buyer("kakao-b"), order(formB.optionId(), 2));
		orderService.place(buyer("kakao-a"), order(formA.optionId(), 3));
	}

	@Test
	@DisplayName("홀드_조회는_홀드_id_가_아니라_판매_폼_id_순이다")
	void 홀드_조회_순서() {
		List<Long> orderIds = jdbcTemplate.queryForList("SELECT id FROM orders ORDER BY id", Long.class);

		List<StockHold> plain = stockHoldRepository.findByOrderIdIn(orderIds);
		List<StockHold> locked = new TransactionTemplate(transactionManager)
				.execute(status -> stockHoldRepository.findByOrderIdInForUpdate(orderIds));

		assertThat(plain).extracting(hold -> hold.getSaleForm().getId())
				.containsExactly(formA.saleFormId(), formB.saleFormId());
		assertThat(locked).extracting(hold -> hold.getSaleForm().getId())
				.containsExactly(formA.saleFormId(), formB.saleFormId());
	}

	@Test
	@DisplayName("만료_배치는_주문_생성과_같은_판매_폼_순서로_재고를_고쳐_교착이_나지_않는다")
	void 만료_배치_잠금_순서() throws Exception {
		jdbcTemplate.update("UPDATE stock_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE");

		// 주문 생성처럼 A → B 순으로 잡는 트랜잭션. A 를 먼저 쥔다
		CountDownLatch holdingA = new CountDownLatch(1);
		CountDownLatch takeB = new CountDownLatch(1);
		ExecutorService creator = Executors.newSingleThreadExecutor();
		Future<?> creating = creator.submit(() -> new TransactionTemplate(transactionManager)
				.executeWithoutResult(status -> {
					lockForm(formA);
					holdingA.countDown();
					await(takeB);
					// 배치가 홀드 id 순(B → A)으로 고쳤다면 B 를 쥔 채 A 를 기다리고 있어 여기서 교착이다
					lockForm(formB);
				}));
		ExecutorService batchThread = Executors.newSingleThreadExecutor();
		try {
			assertThat(holdingA.await(5, TimeUnit.SECONDS)).isTrue();
			Future<Integer> expiring = batchThread.submit(batch::expireOnce);
			Thread.sleep(500);   // 배치가 첫 재고 행에서 막힐 시간
			takeB.countDown();

			creating.get(10, TimeUnit.SECONDS);
			assertThat(expiring.get(10, TimeUnit.SECONDS)).isEqualTo(2);
		} finally {
			takeB.countDown();
			creator.shutdownNow();
			batchThread.shutdownNow();
		}
		assertThat(jdbcTemplate.queryForList("SELECT held FROM sale_form ORDER BY id", Integer.class))
				.containsExactly(0, 0);
	}

	private void lockForm(OrderFixture.Setup form) {
		jdbcTemplate.queryForList("SELECT id FROM sale_form WHERE id = ? FOR UPDATE", form.saleFormId());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}
}
