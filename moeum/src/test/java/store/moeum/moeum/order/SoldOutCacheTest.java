package store.moeum.moeum.order;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 품절 단락 (D-078). 판정은 DB 가 하고, 캐시는 품절로 확인된 요청만 DB 앞에서 거른다.
 *
 * 캐시가 거른 409 는 DB 를 보지 않아 폼 제목을 모른다. 그래서 메시지에 제목이 없다 —
 * 이 차이로 "DB 에 갔는가" 를 구분한다.
 */
class SoldOutCacheTest extends IntegrationTest {

	private static final String CACHE_MESSAGE = "재고가 부족하거나 판매가 마감되었습니다.";

	@Autowired
	private OrderService orderService;

	@Autowired
	private OrderFixture fixture;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private SoldOutCache soldOutCache;

	@BeforeEach
	void setUp() {
		fixture.clean();
		Set<String> keys = redis.keys("moeum:stock:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@AfterEach
	void tearDown() {
		soldOutCache.incrementer(null);
	}

	@Test
	@DisplayName("0행을_받은_요청이_품절_표시를_세우면_다음_요청은_DB에_가지_않고_409")
	void 품절_표시() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		orderService.place(buyer("kakao-first"), order(setup.optionId(), 1));

		// DB 에서 0행 → 품절 표시. 메시지에 폼 제목이 들어 있다 (DB 가 낸 409)
		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class)
				.satisfies(e -> assertThat(e.getMessage()).isNotEqualTo(CACHE_MESSAGE));
		assertThat(redis.opsForValue().get(SoldOutCache.SOLDOUT_FORM + setup.saleFormId())).isEqualTo("0");

		// 표시 값(0) == gen(0) → DB 앞에서 거절. 제목 없는 메시지다
		assertThatThrownBy(() -> orderService.place(buyer("kakao-third"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class)
				.hasMessage(CACHE_MESSAGE);
	}

	@Test
	@DisplayName("홀드가_풀리면_gen이_올라_남아_있는_품절_표시를_무시하고_DB로_간다")
	void 복구하면_표시가_무효() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = buyer("kakao-first");
		OrderGroupResponse held = orderService.place(first, order(setup.optionId(), 1));
		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class);

		orderService.release(first, held.sessionToken());

		// 표시(0)는 지우지 않았다. gen 만 1 로 올랐다
		assertThat(redis.opsForValue().get(SoldOutCache.SOLDOUT_FORM + setup.saleFormId())).isEqualTo("0");
		assertThat(redis.opsForValue().get(SoldOutCache.GEN_FORM + setup.saleFormId())).isEqualTo("1");

		OrderGroupResponse again = orderService.place(buyer("kakao-third"), order(setup.optionId(), 1));
		assertThat(again.sessionToken()).isNotBlank();
	}

	@Test
	@DisplayName("마지막_재고를_잡은_구매자가_같은_선택을_다시_보내면_품절_표시와_상관없이_이어받는다")
	void 같은_선택은_이어받기() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = buyer("kakao-first");
		OrderGroupResponse held = orderService.place(first, order(setup.optionId(), 1));
		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class);

		// 로그인 왕복 뒤 자동 호출이 한 번 더 온 경우 (D-037)
		OrderGroupResponse resumed = orderService.place(first, order(setup.optionId(), 1));

		assertThat(resumed.sessionToken()).isEqualTo(held.sessionToken());
	}

	@Test
	@DisplayName("수량_2개로_실패하면_1개는_남았을_수_있어_품절_표시를_세우지_않는다")
	void 수량_2개_실패() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		orderService.place(buyer("kakao-first"), order(setup.optionId(), 1));   // 폼을 기억시킨다

		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 2)))
				.isInstanceOf(OutOfStockException.class);

		assertThat(redis.hasKey(SoldOutCache.SOLDOUT_FORM + setup.saleFormId())).isFalse();
	}

	@Test
	@DisplayName("품절_표시는_TTL이_지나면_사라진다")
	void TTL() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		orderService.place(buyer("kakao-first"), order(setup.optionId(), 1));
		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class);

		Long ttlMillis = redis.getExpire(SoldOutCache.SOLDOUT_FORM + setup.saleFormId(),
				java.util.concurrent.TimeUnit.MILLISECONDS);
		assertThat(ttlMillis).isBetween(1L, 2_000L);
	}

	@Test
	@DisplayName("커밋_직전_INCR이_한_번_실패해도_곧바로_다시_올려_커밋과_함께_gen이_오른다")
	void 커밋_직전_재시도() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = buyer("kakao-first");
		OrderGroupResponse held = soldOutAfterFirstHold(setup, first);
		AtomicInteger calls = failFirst(1);

		orderService.release(first, held.sessionToken());

		// 커밋 직전 두 번째 시도에서 올랐다. 백그라운드를 기다리지 않는다
		assertThat(calls.get()).isEqualTo(2);
		assertThat(redis.opsForValue().get(SoldOutCache.GEN_FORM + setup.saleFormId())).isEqualTo("1");
		assertThat(orderService.place(buyer("kakao-third"), order(setup.optionId(), 1)).sessionToken()).isNotBlank();
	}

	@Test
	@DisplayName("커밋_직전_두_번_다_실패해도_해제는_커밋되고_커밋_뒤_백그라운드에서_gen을_올린다")
	void 커밋_뒤_재시도() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = buyer("kakao-first");
		OrderGroupResponse held = soldOutAfterFirstHold(setup, first);
		AtomicInteger calls = failFirst(3);   // 커밋 직전 2번 + 커밋 뒤 첫 시도

		orderService.release(first, held.sessionToken());   // Redis 실패로 롤백되지 않는다

		String genKey = SoldOutCache.GEN_FORM + setup.saleFormId();
		assertThat(waitUntil(() -> "1".equals(redis.opsForValue().get(genKey)), 2_000)).isTrue();
		assertThat(calls.get()).isEqualTo(4);
		// 품절 표시(0)가 아직 살아 있어도 gen 1 과 달라 DB 로 가서 산다
		assertThat(redis.hasKey(SoldOutCache.SOLDOUT_FORM + setup.saleFormId())).isTrue();
		assertThat(orderService.place(buyer("kakao-third"), order(setup.optionId(), 1)).sessionToken()).isNotBlank();
	}

	@Test
	@DisplayName("계속_실패하면_커밋_뒤_TTL_더하기_1초에서_포기하고_남은_품절_표시는_TTL로_사라진다")
	void 재시도_포기() throws InterruptedException {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		SessionUser first = buyer("kakao-first");
		OrderGroupResponse held = soldOutAfterFirstHold(setup, first);
		AtomicInteger calls = failFirst(Integer.MAX_VALUE);

		orderService.release(first, held.sessionToken());

		Thread.sleep(3_400);   // TTL 2초 + 1초 + 여유
		int stopped = calls.get();
		assertThat(stopped).isGreaterThan(2);
		Thread.sleep(500);
		assertThat(calls.get()).isEqualTo(stopped);   // 더 시도하지 않는다

		// gen 은 못 올렸지만 해제는 커밋됐다. 표시가 TTL 로 사라졌으니 DB 로 가서 산다
		soldOutCache.incrementer(null);
		assertThat(redis.opsForValue().get(SoldOutCache.GEN_FORM + setup.saleFormId())).isNull();
		assertThat(orderService.place(buyer("kakao-third"), order(setup.optionId(), 1)).sessionToken()).isNotBlank();
	}

	/** 재고 1개를 first 가 잡고, 다음 사람이 0행을 받아 품절 표시(값 0)가 선 상태 */
	private OrderGroupResponse soldOutAfterFirstHold(OrderFixture.Setup setup, SessionUser first) {
		OrderGroupResponse held = orderService.place(first, order(setup.optionId(), 1));
		assertThatThrownBy(() -> orderService.place(buyer("kakao-second"), order(setup.optionId(), 1)))
				.isInstanceOf(OutOfStockException.class);
		assertThat(redis.opsForValue().get(SoldOutCache.SOLDOUT_FORM + setup.saleFormId())).isEqualTo("0");
		return held;
	}

	/** 처음 n 번은 Redis 타임아웃처럼 실패하고, 그 뒤로는 실제로 올린다. 부른 횟수를 돌려준다 */
	private AtomicInteger failFirst(int n) {
		AtomicInteger calls = new AtomicInteger();
		soldOutCache.incrementer(key -> {
			if (calls.incrementAndGet() <= n) {
				throw new org.springframework.dao.QueryTimeoutException("테스트: Redis 타임아웃");
			}
			return redis.opsForValue().increment(key);
		});
		return calls;
	}

	private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return condition.getAsBoolean();
	}

	private static SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, kakaoId);
	}

	private static OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}
}
