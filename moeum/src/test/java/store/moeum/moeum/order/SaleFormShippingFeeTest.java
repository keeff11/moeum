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
import store.moeum.moeum.saleform.PublicProductService;
import store.moeum.moeum.saleform.SaleFormService;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매 폼별 배송비 (D-053).
 *
 * 셀러 기본 배송비 3,000 (OrderFixture). 폼에 값을 넣으면 그것이 이기고,
 * 한 묶음에 폼이 여럿이면 가장 큰 값 하나만 받는다. 무료배송 기준은 셀러 설정 그대로다.
 */
class SaleFormShippingFeeTest extends IntegrationTest {

	private static final int SELLER_FEE = 3000;

	@Autowired
	private OrderService orderService;

	@Autowired
	private CartService cartService;

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private PublicProductService publicProductService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup formA;
	private OrderFixture.Setup formB;

	@BeforeEach
	void setUp() {
		fixture.clean();
		formA = fixture.saleForm(100, null);
		formB = fixture.saleFormOfSameSeller(formA, 100);
	}

	@Test
	@DisplayName("폼에_배송비가_없으면_셀러_기본값이다")
	void 기본값() {
		assertThat(order(item(formA, 1)).shippingFee()).isEqualTo(SELLER_FEE);
	}

	@Test
	@DisplayName("폼에_배송비를_넣으면_그_값이_적용된다")
	void 폼_값() {
		setFormFee(formA, 5000);

		assertThat(order(item(formA, 1)).shippingFee()).isEqualTo(5000);
	}

	@Test
	@DisplayName("0_은_비운_것이_아니라_배송비_없음이다")
	void 영원() {
		setFormFee(formA, 0);

		assertThat(order(item(formA, 1)).shippingFee()).isZero();
	}

	@Test
	@DisplayName("한_묶음에_폼이_여럿이면_가장_큰_배송비_하나만_받는다")
	void 최대값() {
		setFormFee(formA, 5000);
		setFormFee(formB, 2000);

		assertThat(order(item(formA, 1), item(formB, 1)).shippingFee()).isEqualTo(5000);
	}

	@Test
	@DisplayName("폼_값이_있는_폼과_없는_폼이_섞이면_셀러_기본값도_후보다")
	void 섞임() {
		setFormFee(formA, 1000);
		// formB 는 null → 셀러 기본 3,000 이 더 크다

		assertThat(order(item(formA, 1), item(formB, 1)).shippingFee()).isEqualTo(SELLER_FEE);
	}

	@Test
	@DisplayName("무료배송_기준은_폼_배송비에도_그대로_적용된다")
	void 무료배송() {
		setFormFee(formA, 5000);
		jdbcTemplate.update("UPDATE seller SET free_shipping_over = 50000 WHERE id = ?", formA.sellerId());

		// 옵션 A 32,000 × 2 = 64,000 ≥ 50,000
		assertThat(order(item(formA, 2)).shippingFee()).isZero();
	}

	@Test
	@DisplayName("장바구니_예상_배송비도_같은_규칙이다")
	void 장바구니() {
		setFormFee(formA, 5000);
		SessionUser shopper = new SessionUser("kakao-fee-shopper", "구매자");
		cartService.add(shopper, new CartAddRequest(formA.optionId(), 1));
		cartService.add(shopper, new CartAddRequest(formB.optionId(), 1));

		CartResponse cart = cartService.findMine(shopper).get(0);

		assertThat(cart.shippingFee()).isEqualTo(5000);
		assertThat(cart.estimatedShippingFee()).isEqualTo(5000);
	}

	@Test
	@DisplayName("구매자_상품_상세의_배송비는_폼_값을_반영한다")
	void 상품_상세() {
		setFormFee(formA, 5000);

		assertThat(publicProductService.detail(formA.saleFormId()).seller().shippingFee()).isEqualTo(5000);
		assertThat(publicProductService.detail(formB.saleFormId()).seller().shippingFee()).isEqualTo(SELLER_FEE);
	}

	@Test
	@DisplayName("셀러_상세는_폼_값과_적용값을_나눠_주고_수정으로_비울_수_있다")
	void 셀러_상세_수정() {
		String sellerKakaoId = sellerRepository.findById(formA.sellerId()).orElseThrow().getKakaoId();

		SaleFormDetailResponse withFee = saleFormService.update(sellerKakaoId, formA.saleFormId(),
				update(5000));
		assertThat(withFee.shippingFee()).isEqualTo(5000);
		assertThat(withFee.appliedShippingFee()).isEqualTo(5000);

		SaleFormDetailResponse cleared = saleFormService.update(sellerKakaoId, formA.saleFormId(),
				update(null));
		assertThat(cleared.shippingFee()).isNull();
		assertThat(cleared.appliedShippingFee()).isEqualTo(SELLER_FEE);

		assertThat(saleFormService.findHistory(sellerKakaoId, formA.saleFormId()))
				.filteredOn(history -> history.field().equals("shippingFee"))
				.hasSize(2);
	}

	// ---------------------------------------------------------------- 헬퍼

	private static SaleFormUpdate update(Integer shippingFee) {
		return new SaleFormUpdate("테스트 공구", 100, 1, null, null,
				java.time.LocalDateTime.now().plusDays(7), null, null, 0, shippingFee, null, true, List.of());
	}

	private void setFormFee(OrderFixture.Setup form, Integer fee) {
		jdbcTemplate.update("UPDATE sale_form SET shipping_fee = ? WHERE id = ?", fee, form.saleFormId());
	}

	private static OrderCreateRequest.Item item(OrderFixture.Setup form, int qty) {
		return new OrderCreateRequest.Item(form.optionId(), qty);
	}

	private store.moeum.moeum.order.dto.OrderGroupResponse order(OrderCreateRequest.Item... items) {
		return orderService.place(new SessionUser("kakao-fee-buyer-" + System.nanoTime(), "구매자"),
				new OrderCreateRequest(List.of(items)));
	}
}
