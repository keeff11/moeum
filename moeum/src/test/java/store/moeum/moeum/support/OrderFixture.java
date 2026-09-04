package store.moeum.moeum.support;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
	private final JdbcTemplate jdbcTemplate;

	public record Setup(Long sellerId, Long saleFormId, Long optionId, Long secondOptionId) {
	}

	/** FK 순서대로 지운다 */
	@Transactional
	public void clean() {
		for (String table : new String[]{
				"stock_hold", "order_item", "orders", "order_group",
				"cart_item", "cart", "buyer_address", "buyer",
				"sale_form_history", "product_option", "product", "sale_form", "seller"}) {
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
