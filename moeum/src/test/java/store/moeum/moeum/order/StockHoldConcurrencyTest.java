package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.HoldStatus;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3단계의 핵심. 손으로 확인할 수 없는 구간이라 여기서만 검증된다.
 *
 * 확인 조건: 재고 100 개에 스레드 200 개를 던져도 sold + held <= 100.
 */
class StockHoldConcurrencyTest extends IntegrationTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private StockHoldRepository stockHoldRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	@Test
	@DisplayName("동시에_N명이_주문하면_재고만큼만_성공한다")
	void 동시에_N명이_주문하면_재고만큼만_성공한다() throws Exception {
		int stock = 100;
		int threads = 200;
		OrderFixture.Setup setup = fixture.saleForm(stock, null);

		AtomicInteger success = new AtomicInteger();
		AtomicInteger outOfStock = new AtomicInteger();
		AtomicInteger other = new AtomicInteger();

		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		ExecutorService pool = Executors.newFixedThreadPool(32);

		for (int i = 0; i < threads; i++) {
			int index = i;
			pool.submit(() -> {
				SessionUser buyer = new SessionUser("kakao-buyer-" + index, "구매자" + index);
				ready.countDown();
				try {
					start.await();
					orderService.place(buyer, order(setup.optionId(), 1));
					success.incrementAndGet();
				} catch (OutOfStockException e) {
					outOfStock.incrementAndGet();
				} catch (Exception e) {
					other.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}

		ready.await(30, TimeUnit.SECONDS);
		start.countDown();                       // 200개를 한꺼번에 푼다
		assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
		pool.shutdown();

		int held = intOf("SELECT held FROM sale_form WHERE id = ?", setup.saleFormId());
		int sold = intOf("SELECT sold FROM sale_form WHERE id = ?", setup.saleFormId());

		assertThat(other).as("품절 외의 예외가 나면 안 된다").hasValue(0);
		assertThat(success.get()).as("재고만큼만 성공").isEqualTo(stock);
		assertThat(outOfStock.get()).isEqualTo(threads - stock);

		// ★ 확인 조건
		assertThat(held + sold).as("sold + held <= stock_max").isLessThanOrEqualTo(stock);
		assertThat(held).isEqualTo(stock);
		assertThat(sold).isZero();
		assertThat(stockHoldRepository.countByStatus(HoldStatus.HELD)).isEqualTo(stock);
	}

	@Test
	@DisplayName("품절이면_OutOfStockException_이고_재시도하지_않는다")
	void 품절이면_OutOfStockException_이고_재시도하지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = new SessionUser("kakao-first", "먼저");
		SessionUser second = new SessionUser("kakao-second", "나중");

		orderService.place(first, order(setup.optionId(), 1));

		long before = countHoldAttempts(setup.saleFormId());

		assertThatThrownBy(() -> orderService.place(second, order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.OUT_OF_STOCK);

		// 재시도했다면 홀드 행이 늘거나 재고가 움직였을 것이다
		assertThat(countHoldAttempts(setup.saleFormId())).isEqualTo(before);
		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", setup.saleFormId())).isEqualTo(1);
	}

	@Test
	@DisplayName("여러_폼을_담으면_하나만_품절이어도_전체_롤백된다")
	void 여러_폼을_담으면_하나만_품절이어도_전체_롤백된다() {
		OrderFixture.Setup plenty = fixture.saleForm(100, null);
		OrderFixture.Setup scarce = fixture.saleFormOfSameSeller(plenty, 1);

		// 다른 구매자가 부족한 쪽을 먼저 가져간다
		orderService.place(new SessionUser("kakao-taker", "선점"), order(scarce.optionId(), 1));

		SessionUser buyer = new SessionUser("kakao-both", "둘다");

		assertThatThrownBy(() -> orderService.place(buyer, new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(plenty.optionId(), 2),
				new OrderCreateRequest.Item(scarce.optionId(), 1)))))
				.isInstanceOf(OutOfStockException.class);

		// ★ 넉넉한 쪽도 확보되면 안 된다. 배송비를 나눌 수 없어 부분 성공이 성립하지 않는다
		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", plenty.saleFormId()))
				.as("전체 롤백")
				.isZero();
		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", scarce.saleFormId()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("같은_폼의_여러_옵션은_합쳐서_한_번에_확보한다")
	void 같은_폼의_여러_옵션은_합쳐서_한_번에_확보한다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		orderService.place(new SessionUser("kakao-multi", "여러옵션"), new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(setup.optionId(), 2),
				new OrderCreateRequest.Item(setup.secondOptionId(), 3))));

		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", setup.saleFormId())).isEqualTo(5);
	}

	@Test
	@DisplayName("1인당_구매_상한을_넘으면_확보하지_않는다")
	void 일인당_구매_상한을_넘으면_확보하지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(100, 2);

		assertThatThrownBy(() -> orderService.place(
				new SessionUser("kakao-greedy", "과다"), order(setup.optionId(), 3)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.MAX_PER_USER_EXCEEDED);

		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", setup.saleFormId())).isZero();
	}

	@Test
	@DisplayName("마감된_폼은_확보하지_않는다")
	void 마감된_폼은_확보하지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(100, null);
		jdbcTemplate.update("UPDATE sale_form SET closes_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?",
				setup.saleFormId());

		assertThatThrownBy(() -> orderService.place(
				new SessionUser("kakao-late", "지각"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class);

		assertThat(intOf("SELECT held FROM sale_form WHERE id = ?", setup.saleFormId())).isZero();
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}

	private int intOf(String sql, Object arg) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
		return value == null ? 0 : value;
	}

	private long countHoldAttempts(Long saleFormId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM stock_hold WHERE sale_form_id = ?", Long.class, saleFormId);
		return count == null ? 0 : count;
	}
}
