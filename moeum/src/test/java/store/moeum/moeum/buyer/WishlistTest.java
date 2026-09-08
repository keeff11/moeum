package store.moeum.moeum.buyer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.buyer.dto.WishlistResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 찜(하트) (D-029).
 *
 * <b>하트는 연타되는 버튼이다.</b> 두 번 눌렀다고 409 가 나가면 화면이 깜빡이고,
 * 뗀 것을 또 떼도 마찬가지다. 확인하려는 것은 그 멱등성과, 미발행 폼이 새지 않는가다.
 */
class WishlistTest extends IntegrationTest {

	@Autowired
	private WishlistService wishlistService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;

	@BeforeEach
	void setUp() {
		fixture.clean();
		setup = fixture.saleForm(10, null);
	}

	// ---------------------------------------------------------------- 찜 · 해제

	@Test
	@DisplayName("찜하면_목록에_담긴다")
	void 찜() {
		wishlistService.add(buyer(), setup.saleFormId());

		WishlistResponse response = WishlistResponse.of(wishlistService.saleFormIds(buyer()));
		assertThat(response.saleFormIds()).containsExactly(setup.saleFormId());
		assertThat(response.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("두_번_찜해도_한_건만_남는다")
	void 찜_멱등() {
		wishlistService.add(buyer(), setup.saleFormId());
		wishlistService.add(buyer(), setup.saleFormId());

		// 하트는 연타된다. 두 번째에 409 가 나가면 화면이 깜빡인다
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	@DisplayName("찜을_떼면_목록에서_빠진다")
	void 해제() {
		wishlistService.add(buyer(), setup.saleFormId());
		wishlistService.remove(buyer(), setup.saleFormId());

		assertThat(wishlistService.saleFormIds(buyer())).isEmpty();
	}

	@Test
	@DisplayName("찜한_적_없는_것을_떼도_터지지_않는다")
	void 해제_멱등() {
		wishlistService.remove(buyer(), setup.saleFormId());

		assertThat(rows()).isZero();
	}

	@Test
	@DisplayName("뗐다_다시_찜할_수_있다")
	void 다시_찜() {
		wishlistService.add(buyer(), setup.saleFormId());
		wishlistService.remove(buyer(), setup.saleFormId());
		wishlistService.add(buyer(), setup.saleFormId());

		assertThat(rows()).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 구매자별로 갈린다

	@Test
	@DisplayName("남의_찜은_보이지_않는다")
	void 사용자별() {
		wishlistService.add(buyer(), setup.saleFormId());

		SessionUser other = new SessionUser("kakao-other-buyer", "다른 구매자");
		assertThat(wishlistService.saleFormIds(other)).isEmpty();
	}

	@Test
	@DisplayName("주문한_적_없는_사용자도_찜할_수_있다")
	void 첫_사용자() {
		// 주문 한 번 없이 하트부터 누르는 것이 정상이다. buyer 행이 여기서 만들어진다
		wishlistService.add(new SessionUser("kakao-brand-new", "신규"), setup.saleFormId());

		assertThat(rows()).isEqualTo(1);
	}

	@Test
	@DisplayName("찜한_적_없는_사용자의_목록은_빈_배열이다")
	void 빈_목록() {
		// buyer 행이 없다고 404 를 주면 첫 방문자마다 오류 화면을 보게 된다
		assertThat(wishlistService.saleFormIds(new SessionUser("kakao-nobody", "아무개"))).isEmpty();
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("미발행_폼은_찜할_수_없다")
	void 미발행() {
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				SaleFormStatus.DRAFT.name(), setup.saleFormId());

		// 403 이면 그 id 에 폼이 있다는 사실이 새어 나간다
		assertThatThrownBy(() -> wishlistService.add(buyer(), setup.saleFormId()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	@Test
	@DisplayName("없는_폼은_찜할_수_없다")
	void 없는_폼() {
		assertThatThrownBy(() -> wishlistService.add(buyer(), 999_999L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	@Test
	@DisplayName("마감된_폼도_찜은_유지된다")
	void 마감_후() {
		wishlistService.add(buyer(), setup.saleFormId());
		jdbcTemplate.update("UPDATE sale_form SET status = 'CLOSED' WHERE id = ?", setup.saleFormId());

		// 지난 공구를 찜해 둔 채로 다음 회차를 기다리는 것이 자연스럽다
		assertThat(wishlistService.saleFormIds(buyer())).containsExactly(setup.saleFormId());
	}

	// ---------------------------------------------------------------- 도우미

	private static SessionUser buyer() {
		return new SessionUser("kakao-wish-buyer", "찜 구매자");
	}

	private int rows() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishlist", Integer.class);
	}
}
