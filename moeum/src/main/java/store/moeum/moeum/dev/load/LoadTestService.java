package store.moeum.moeum.dev.load;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * HTTP 부하 테스트(scripts/load)의 준비 · 정리. <b>로컬 프로파일에서만 만들어진다.</b>
 *
 * 만드는 데이터에는 전부 {@code load-} 접두어를 붙이고, 정리할 때도 그 접두어로만 지운다.
 * 로컬 DB 에 손으로 만든 주문이 있어도 건드리지 않는다.
 */
@Service
@Profile("local")
@RequiredArgsConstructor
public class LoadTestService {

	static final String BUYER_PREFIX = "load-buyer-";
	private static final String FORM_PREFIX = "load-form-";
	private static final String SELLER_PREFIX = "load-seller-";

	/** 테스트용 폼에 딸린 주문 · 구매자를 고르는 조건. 외래 키 순서대로 지울 때 반복해서 쓴다 */
	private static final String LOAD_FORMS = "(SELECT id FROM sale_form WHERE slug LIKE '" + FORM_PREFIX + "%')";
	private static final String LOAD_BUYERS = "(SELECT id FROM buyer WHERE kakao_id LIKE '" + BUYER_PREFIX + "%')";

	private final SellerRepository sellerRepository;
	private final SaleFormRepository saleFormRepository;
	private final JdbcTemplate jdbcTemplate;

	public record Fixture(Long saleFormId, Long optionId, int stockMax) {
	}

	public record Snapshot(Long saleFormId, int stockMax, int held, int sold, long heldRows, long orderGroups) {
	}

	@Transactional
	public Fixture createSaleForm(int stockMax) {
		long unique = System.nanoTime();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId(SELLER_PREFIX + unique)
				.storeSlug("load-" + unique)
				.shippingFee(3000)
				.build());
		seller.approve();

		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("부하 테스트용 공구")
				.slug(FORM_PREFIX + unique)
				.saleType(SaleType.GROUP)
				.stockMax(stockMax)
				.targetQty(1)
				.closesAt(LocalDateTime.now().plusDays(1))
				.minOrderAmount(0)
				.build();

		Product product = Product.builder().name("부하 테스트 상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);
		// 판매 중이어야 조건부 UPDATE 의 status = 'SELLING' 조건을 통과한다
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				SaleFormStatus.SELLING.name(), form.getId());

		return new Fixture(form.getId(), form.getProducts().get(0).getOptions().get(0).getId(), stockMax);
	}

	public Snapshot snapshot(Long saleFormId) {
		Map<String, Object> form = jdbcTemplate.queryForMap(
				"SELECT stock_max, held, sold FROM sale_form WHERE id = ?", saleFormId);
		Long heldRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM stock_hold WHERE sale_form_id = ? AND status = 'HELD'", Long.class, saleFormId);
		Long groups = jdbcTemplate.queryForObject(
				"SELECT COUNT(DISTINCT order_group_id) FROM orders WHERE sale_form_id = ?", Long.class, saleFormId);
		return new Snapshot(saleFormId,
				((Number) form.get("stock_max")).intValue(),
				((Number) form.get("held")).intValue(),
				((Number) form.get("sold")).intValue(),
				heldRows == null ? 0 : heldRows,
				groups == null ? 0 : groups);
	}

	/** 외래 키 순서대로 지운다. 부하 테스트가 만든 행만 대상이다 */
	@Transactional
	public void cleanUp() {
		jdbcTemplate.update("DELETE FROM stock_hold WHERE sale_form_id IN " + LOAD_FORMS);
		jdbcTemplate.update("DELETE FROM order_item WHERE order_id IN "
				+ "(SELECT id FROM orders WHERE sale_form_id IN " + LOAD_FORMS + ")");
		jdbcTemplate.update("DELETE FROM orders WHERE sale_form_id IN " + LOAD_FORMS);
		jdbcTemplate.update("DELETE FROM order_group WHERE buyer_id IN " + LOAD_BUYERS
				+ " AND id NOT IN (SELECT order_group_id FROM orders)");
		jdbcTemplate.update("DELETE FROM buyer WHERE kakao_id LIKE '" + BUYER_PREFIX + "%'"
				+ " AND id NOT IN (SELECT buyer_id FROM order_group)");
		jdbcTemplate.update("DELETE FROM sale_form_history WHERE sale_form_id IN " + LOAD_FORMS);
		jdbcTemplate.update("DELETE FROM product_option WHERE product_id IN "
				+ "(SELECT id FROM product WHERE sale_form_id IN " + LOAD_FORMS + ")");
		jdbcTemplate.update("DELETE FROM product WHERE sale_form_id IN " + LOAD_FORMS);
		jdbcTemplate.update("DELETE FROM sale_form WHERE slug LIKE '" + FORM_PREFIX + "%'");
		jdbcTemplate.update("DELETE FROM seller WHERE kakao_id LIKE '" + SELLER_PREFIX + "%'");
	}
}
