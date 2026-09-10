package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만료 배치가 최종 안전망이다.
 * 프론트의 /release 는 브라우저 강제 종료·앱 전환이면 오지 않으므로 이게 없으면 재고가 영영 안 풀린다.
 */
class HoldExpiryBatchTest extends IntegrationTest {

	@Autowired
	private OrderService orderService;

	@Autowired
	private HoldExpiryBatch batch;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	@Test
	@DisplayName("만료된_홀드는_배치가_회수한다")
	void 만료된_홀드는_배치가_회수한다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		orderService.place(buyer("kakao-leaver"), order(setup.optionId(), 3));

		assertThat(held(setup)).isEqualTo(3);

		expireAllHolds();
		int released = batch.expireOnce();

		assertThat(released).isEqualTo(1);
		assertThat(held(setup)).isZero();
		assertThat(holdStatus()).containsExactly("RELEASED");
		assertThat(groupStatus()).containsExactly("EXPIRED");
	}

	@Test
	@DisplayName("아직_만료되지_않은_홀드는_건드리지_않는다")
	void 아직_만료되지_않은_홀드는_건드리지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		orderService.place(buyer("kakao-active"), order(setup.optionId(), 2));

		assertThat(batch.expireOnce()).isZero();
		assertThat(held(setup)).isEqualTo(2);
		assertThat(holdStatus()).containsExactly("HELD");
	}

	@Test
	@DisplayName("배치가_두_번_돌아도_재고가_두_번_돌아가지_않는다")
	void 배치가_두_번_돌아도_재고가_두_번_돌아가지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		orderService.place(buyer("kakao-twice"), order(setup.optionId(), 4));

		expireAllHolds();

		assertThat(batch.expireOnce()).isEqualTo(1);
		assertThat(held(setup)).isZero();

		// ★ 멱등 가드. 두 번째 실행은 아무것도 하지 않아야 한다
		assertThat(batch.expireOnce()).isZero();
		assertThat(held(setup)).isZero();
	}

	@Test
	@DisplayName("회수된_재고는_다른_구매자가_가져갈_수_있다")
	void 회수된_재고는_다른_구매자가_가져갈_수_있다() {
		OrderFixture.Setup setup = fixture.saleForm(1, null);
		orderService.place(buyer("kakao-ghost"), order(setup.optionId(), 1));

		expireAllHolds();
		batch.expireOnce();

		OrderGroupResponse response = orderService.place(buyer("kakao-next"), order(setup.optionId(), 1));

		assertThat(response.sessionToken()).startsWith("cs_");
		assertThat(held(setup)).isEqualTo(1);
	}

	@Test
	@DisplayName("release를_두_번_불러도_재고가_두_번_돌아가지_않는다")
	void release를_두_번_불러도_재고가_두_번_돌아가지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-release");
		OrderGroupResponse response = orderService.place(user, order(setup.optionId(), 3));

		orderService.release(user, response.sessionToken());
		assertThat(held(setup)).isZero();

		orderService.release(user, response.sessionToken());
		assertThat(held(setup)).isZero();
	}

	// ---------------------------------------------------------------- 이어받기 (D-037)

	@Test
	@DisplayName("같은_선택으로_다시_부르면_홀드를_두_번_잡지_않는다")
	void 이어받기() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-resume");

		OrderGroupResponse first = orderService.place(user, order(setup.optionId(), 3));
		OrderGroupResponse again = orderService.place(user, order(setup.optionId(), 3));

		// 로그인 왕복 뒤 프론트의 자동 호출이 중복되는 자리다. 새로 잡으면 재고가 두 배로 묶인다
		assertThat(again.sessionToken()).isEqualTo(first.sessionToken());
		assertThat(held(setup)).isEqualTo(3);
		assertThat(groupStatus()).hasSize(1);
	}

	@Test
	@DisplayName("선택이_다르면_이어받지_않는다")
	void 선택이_다르면_새로_잡는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-changed");

		OrderGroupResponse first = orderService.place(user, order(setup.optionId(), 3));
		OrderGroupResponse other = orderService.place(user, order(setup.optionId(), 5));

		// 방금 고른 것과 다른 것을 결제하게 할 수는 없다
		assertThat(other.sessionToken()).isNotEqualTo(first.sessionToken());
		assertThat(held(setup)).isEqualTo(8);
	}

	@Test
	@DisplayName("다른_구매자의_세션은_이어받지_않는다")
	void 남의_세션은_이어받지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		OrderGroupResponse mine = orderService.place(buyer("kakao-a"), order(setup.optionId(), 2));

		OrderGroupResponse theirs = orderService.place(buyer("kakao-b"), order(setup.optionId(), 2));

		assertThat(theirs.sessionToken()).isNotEqualTo(mine.sessionToken());
		assertThat(held(setup)).isEqualTo(4);
	}

	@Test
	@DisplayName("홀드가_만료된_세션은_이어받지_않고_새로_잡는다")
	void 만료된_세션은_이어받지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		SessionUser user = buyer("kakao-stale");
		OrderGroupResponse first = orderService.place(user, order(setup.optionId(), 2));
		expireAllHolds();

		OrderGroupResponse fresh = orderService.place(user, order(setup.optionId(), 2));

		// 만료 배치가 아직 안 돌았을 뿐인 세션을 주면 남은 시간 0인 화면을 받고 결제에서 튕긴다
		assertThat(fresh.sessionToken()).isNotEqualTo(first.sessionToken());
	}

	private void expireAllHolds() {
		jdbcTemplate.update("UPDATE stock_hold SET expires_at = NOW(6) - INTERVAL 1 MINUTE");
	}

	private int held(OrderFixture.Setup setup) {
		Integer value = jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
		return value == null ? 0 : value;
	}

	private List<String> holdStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM stock_hold", String.class);
	}

	private List<String> groupStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM order_group", String.class);
	}

	private SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}
}
