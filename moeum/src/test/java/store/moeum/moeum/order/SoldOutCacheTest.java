package store.moeum.moeum.order;

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

	@BeforeEach
	void setUp() {
		fixture.clean();
		Set<String> keys = redis.keys("moeum:stock:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
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

	private static SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, kakaoId);
	}

	private static OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}
}
