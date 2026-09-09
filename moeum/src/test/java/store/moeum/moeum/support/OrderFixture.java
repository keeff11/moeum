package store.moeum.moeum.support;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerAddress;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;

/** 주문·홀드 테스트용 판매 폼을 만든다 */
@TestComponent
@RequiredArgsConstructor
public class OrderFixture {

	private final SellerRepository sellerRepository;
	private final SaleFormRepository saleFormRepository;
	private final BuyerRepository buyerRepository;
	private final BuyerAddressRepository buyerAddressRepository;
	private final JdbcTemplate jdbcTemplate;

	public record Setup(Long sellerId, Long saleFormId, Long optionId, Long secondOptionId) {
	}

	/** FK 순서대로 지운다 */
	@Transactional
	public void clean() {
		for (String table : new String[]{
				"second_charge", "payment_event", "refund", "payment", "outbox",
				"stock_hold", "order_item", "orders", "shipping", "order_group",
				"cart_item", "cart", "wishlist", "buyer_address", "buyer",
				"sale_form_history", "sale_form_image", "product_option", "product", "sale_form", "seller"}) {
			jdbcTemplate.execute("DELETE FROM " + table);
		}
	}

	@Transactional
	public Setup saleForm(int stockMax, Integer maxPerUser) {
		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-seller-" + System.nanoTime())
				.storeSlug("store-" + System.nanoTime())
				.shippingFee(3000)
				.build());
		seller.approve();
		return create(seller, stockMax, maxPerUser);
	}

	/**
	 * 구매자와 배송지를 미리 만들어 둔다.
	 *
	 * <b>/pay 가 배송지를 요구한다</b> (D-033) — 없으면 SHIPPING_ADDRESS_REQUIRED 로 막힌다.
	 * 결제까지 가는 테스트는 place() 전에 이걸 한 번 불러야 한다.
	 *
	 * @return 수령인 이름. 셀러 화면에 찍히는 값이라 카카오 닉네임과 다르다
	 */
	@Transactional
	public String buyerWithAddress(String kakaoId, String recipientName) {
		Buyer buyer = buyerRepository.findByKakaoId(kakaoId)
				.orElseGet(() -> buyerRepository.save(Buyer.of(kakaoId, "구매자")));

		if (buyerAddressRepository.findByBuyerId(buyer.getId()).isEmpty()) {
			buyerAddressRepository.save(BuyerAddress.builder()
					.buyer(buyer)
					.recipientName(recipientName)
					.phone("01012345678")
					.postalCode("06236")
					.address1("서울 강남구 테헤란로 1")
					.address2("2층")
					.build());
		}
		return recipientName;
	}

	/** 같은 셀러의 두 번째 폼. 여러 폼을 한 묶음에 담는 테스트용 */
	@Transactional
	public Setup saleFormOfSameSeller(Setup existing, int stockMax) {
		Seller seller = sellerRepository.findById(existing.sellerId()).orElseThrow();
		return create(seller, stockMax, null);
	}

	private Setup create(Seller seller, int stockMax, Integer maxPerUser) {
		long unique = System.nanoTime();

		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("테스트 공구 " + unique)
				.slug("form-" + unique)
				.saleType(SaleType.GROUP)
				.stockMax(stockMax)
				.targetQty(1)
				.maxPerUser(maxPerUser)
				.closesAt(java.time.LocalDateTime.now().plusDays(7))
				.minOrderAmount(0)
				.build();

		Product product = Product.builder().name("상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		product.addOption(ProductOption.builder()
				.name("옵션 B").deposit1Amount(25000).deposit2Amount(10000).sortOrder(1).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);

		// 판매 중이어야 조건부 UPDATE 의 status = 'SELLING' 조건을 통과한다
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				SaleFormStatus.SELLING.name(), form.getId());

		Product saved = form.getProducts().get(0);
		return new Setup(seller.getId(), form.getId(),
				saved.getOptions().get(0).getId(), saved.getOptions().get(1).getId());
	}
}
