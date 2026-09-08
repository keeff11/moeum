package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.cart.CartService;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무료배송 기준이 실제 청구에 반영되는가.
 *
 * <b>Seller.shippingFeeFor 는 처음부터 있었는데 아무도 부르지 않았다.</b>
 * freeShippingOver 가 장바구니·상품상세·셀러페이지 세 군데 응답에 실려 나가는 동안
 * 청구는 늘 기본 배송비로 나갔다 — 구매자는 "무료배송" 을 보고 2차금에서 배송비를 냈다.
 *
 * 옵션 1차금 20,000 · 2차금 12,000 → 상품 총액은 수량 × 32,000 이다.
 */
class FreeShippingTest extends IntegrationTest {

	private static final int SHIPPING_FEE = 3000;
	private static final int FREE_OVER = 50000;
	private static final int OPTION_TOTAL = 32000;   // 20,000 + 12,000

	@Autowired
	private OrderService orderService;

	@Autowired
	private CartService cartService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;

	@BeforeEach
	void setUp() {
		fixture.clean();
		setup = fixture.saleForm(100, null);
		jdbcTemplate.update("UPDATE seller SET shipping_fee = ?, free_shipping_over = ? WHERE id = ?",
				SHIPPING_FEE, FREE_OVER, setup.sellerId());
	}

	// ---------------------------------------------------------------- 주문 청구

	@Test
	@DisplayName("기준을_넘으면_배송비가_붙지_않는다")
	void 면제() {
		// 32,000 × 2 = 64,000 ≥ 50,000
		OrderGroupResponse response = order(2);

		assertThat(response.shippingFee()).isZero();
		assertThat(shippingFeeInDb()).isZero();
	}

	@Test
	@DisplayName("기준에_못_미치면_배송비가_붙는다")
	void 미달() {
		// 32,000 < 50,000
		assertThat(order(1).shippingFee()).isEqualTo(SHIPPING_FEE);
	}

	@Test
	@DisplayName("기준_금액과_같으면_면제된다")
	void 경계() {
		jdbcTemplate.update("UPDATE seller SET free_shipping_over = ? WHERE id = ?",
				OPTION_TOTAL, setup.sellerId());

		// "이 금액 이상" 이라고 안내한다. 딱 맞을 때 붙으면 안내와 다르다
		assertThat(order(1).shippingFee()).isZero();
	}

	@Test
	@DisplayName("무료배송_기준이_없으면_늘_배송비가_붙는다")
	void 미설정() {
		jdbcTemplate.update("UPDATE seller SET free_shipping_over = NULL WHERE id = ?", setup.sellerId());

		assertThat(order(10).shippingFee()).isEqualTo(SHIPPING_FEE);
	}

	@Test
	@DisplayName("기준은_1차금이_아니라_상품_총액으로_본다")
	void 기준_금액() {
		// 1차금만 세면 20,000 × 2 = 40,000 이라 미달이지만,
		// 상품 총액은 64,000 이라 면제다. 잔금이 큰 공구에서 기준을 못 넘기면 안 된다
		assertThat(order(2).shippingFee()).isZero();
	}

	// ---------------------------------------------------------------- 장바구니 표시

	@Test
	@DisplayName("장바구니가_보여주는_배송비와_실제_청구액이_같다")
	void 장바구니와_일치() {
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 2));
		CartResponse cart = cartService.findMine(buyer()).get(0);

		// 화면에 "무료배송" 이라 떠 놓고 2차금에서 배송비가 붙으면 안 된다
		assertThat(cart.estimatedShippingFee()).isZero();
		assertThat(order(2).shippingFee()).isEqualTo(cart.estimatedShippingFee());
	}

	@Test
	@DisplayName("장바구니의_기본_배송비는_그대로_준다")
	void 장바구니_기본값() {
		cartService.add(buyer(), new CartAddRequest(setup.optionId(), 1));
		CartResponse cart = cartService.findMine(buyer()).get(0);

		// 화면이 "3,000원 → 무료" 같은 표시를 하려면 원래 값도 필요하다
		assertThat(cart.shippingFee()).isEqualTo(SHIPPING_FEE);
		assertThat(cart.freeShippingOver()).isEqualTo(FREE_OVER);
		assertThat(cart.estimatedShippingFee()).isEqualTo(SHIPPING_FEE);
	}

	// ---------------------------------------------------------------- 도우미

	private OrderGroupResponse order(int qty) {
		return orderService.place(buyer(), new OrderCreateRequest(
				List.of(new OrderCreateRequest.Item(setup.optionId(), qty))));
	}

	private int shippingFeeInDb() {
		return jdbcTemplate.queryForObject(
				"SELECT shipping_fee FROM order_group ORDER BY id DESC LIMIT 1", Integer.class);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-free-shipping", "무료배송 구매자");
	}
}
