package store.moeum.moeum.cart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.buyer.AddressService;
import store.moeum.moeum.buyer.dto.AddressRequest;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartServiceTest extends IntegrationTest {

	private static final SessionUser BUYER = new SessionUser("kakao-cart-buyer", "구매자");

	@Autowired
	private CartService cartService;

	@Autowired
	private AddressService addressService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	@Test
	@DisplayName("담으면_셀러별_장바구니가_만들어진다")
	void 담으면_셀러별_장바구니가_만들어진다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 2));

		List<CartResponse> carts = cartService.findMine(BUYER);
		assertThat(carts).hasSize(1);
		assertThat(carts.get(0).sellerId()).isEqualTo(setup.sellerId());
		assertThat(carts.get(0).items()).hasSize(1);
		assertThat(carts.get(0).items().get(0).qty()).isEqualTo(2);
		assertThat(carts.get(0).shippingFee()).isEqualTo(3000);
	}

	@Test
	@DisplayName("같은_옵션을_다시_담으면_수량이_더해진다")
	void 같은_옵션을_다시_담으면_수량이_더해진다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 2));
		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 3));

		List<CartResponse> carts = cartService.findMine(BUYER);
		assertThat(carts.get(0).items()).hasSize(1);
		assertThat(carts.get(0).items().get(0).qty()).isEqualTo(5);
	}

	@Test
	@DisplayName("다른_셀러_상품은_장바구니가_따로_생긴다")
	void 다른_셀러_상품은_장바구니가_따로_생긴다() {
		OrderFixture.Setup first = fixture.saleForm(10, null);
		OrderFixture.Setup second = fixture.saleForm(10, null);

		cartService.add(BUYER, new CartAddRequest(first.optionId(), 1));
		cartService.add(BUYER, new CartAddRequest(second.optionId(), 1));

		// 교체가 아니라 셀러마다 하나씩이다
		assertThat(cartService.findMine(BUYER)).hasSize(2);
	}

	@Test
	@DisplayName("장바구니는_재고를_잡지_않는다")
	void 장바구니는_재고를_잡지_않는다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 5));

		Integer held = jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId());
		assertThat(held).isZero();
	}

	@Test
	@DisplayName("담아둔_사이_품절되면_상태로_표시된다")
	void 담아둔_사이_품절되면_상태로_표시된다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 2));

		jdbcTemplate.update("UPDATE sale_form SET sold = 10 WHERE id = ?", setup.saleFormId());

		CartResponse cart = cartService.findMine(BUYER).get(0);
		assertThat(cart.items().get(0).status()).isEqualTo(CartResponse.ItemStatus.SOLD_OUT);
		assertThat(cart.orderable()).isFalse();
	}

	@Test
	@DisplayName("담아둔_사이_마감되면_상태로_표시된다")
	void 담아둔_사이_마감되면_상태로_표시된다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 2));

		jdbcTemplate.update("UPDATE sale_form SET closes_at = NOW(6) - INTERVAL 1 MINUTE WHERE id = ?",
				setup.saleFormId());

		CartResponse cart = cartService.findMine(BUYER).get(0);
		assertThat(cart.items().get(0).status()).isEqualTo(CartResponse.ItemStatus.CLOSED);
		assertThat(cart.orderable()).isFalse();
	}

	@Test
	@DisplayName("남은_재고보다_많이_담으면_상태로_표시된다")
	void 남은_재고보다_많이_담으면_상태로_표시된다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 8));

		jdbcTemplate.update("UPDATE sale_form SET sold = 5 WHERE id = ?", setup.saleFormId());

		CartResponse cart = cartService.findMine(BUYER).get(0);
		assertThat(cart.items().get(0).status()).isEqualTo(CartResponse.ItemStatus.NOT_ENOUGH_STOCK);
	}

	@Test
	@DisplayName("없는_옵션을_담으면_404다")
	void 없는_옵션을_담으면_404다() {
		assertThatThrownBy(() -> cartService.add(BUYER, new CartAddRequest(99999L, 1)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.OPTION_NOT_FOUND);
	}

	@Test
	@DisplayName("남의_장바구니_항목은_지울_수_없다")
	void 남의_장바구니_항목은_지울_수_없다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		cartService.add(BUYER, new CartAddRequest(setup.optionId(), 1));
		Long cartItemId = cartService.findMine(BUYER).get(0).items().get(0).cartItemId();

		SessionUser other = new SessionUser("kakao-other-buyer", "남");
		cartService.add(other, new CartAddRequest(setup.optionId(), 1));

		assertThatThrownBy(() -> cartService.remove(other, cartItemId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND);
	}

	@Test
	@DisplayName("배송지는_등록_전이면_비어_있고_PUT하면_저장된다")
	void 배송지는_등록_전이면_비어_있고_PUT하면_저장된다() {
		assertThat(addressService.find(BUYER)).isEmpty();

		addressService.save(BUYER, new AddressRequest(
				"홍길동", "010-1234-5678", "12345", "서울시 어딘가", "101동 202호", "부재시 경비실"));

		assertThat(addressService.find(BUYER)).isPresent()
				.get()
				.satisfies(address -> {
					assertThat(address.recipientName()).isEqualTo("홍길동");
					assertThat(address.memo()).isEqualTo("부재시 경비실");
				});
	}

	@Test
	@DisplayName("배송지_PUT은_전체_교체이고_구매자당_한_행만_유지된다")
	void 배송지_PUT은_전체_교체이고_구매자당_한_행만_유지된다() {
		addressService.save(BUYER, new AddressRequest(
				"홍길동", "010-1234-5678", "12345", "서울시 어딘가", "101동", "메모"));
		addressService.save(BUYER, new AddressRequest(
				"김철수", "010-9999-8888", null, "부산시 어딘가", null, null));

		Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM buyer_address", Long.class);
		assertThat(rows).isEqualTo(1);

		assertThat(addressService.find(BUYER)).get().satisfies(address -> {
			assertThat(address.recipientName()).isEqualTo("김철수");
			// 보내지 않은 선택 항목은 비워진다
			assertThat(address.memo()).isNull();
			assertThat(address.postalCode()).isNull();
		});
	}
}
