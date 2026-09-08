package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.PublicStatus;
import store.moeum.moeum.saleform.dto.StorePageResponse;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 셀러 페이지 (B0) — 헤더 + 상품 목록.
 *
 * 셀러가 링크를 뿌리고 구매자가 그 링크로 들어오는 구조라 <b>이 화면이 유입의 시작점이다.</b>
 * 여기서 새면 안 되는 것 두 가지를 특히 본다 — 미발행(DRAFT) 폼과 미승인 셀러.
 */
class StorePageTest extends IntegrationTest {

	private static final String SLUG = "moeum-store";

	@Autowired
	private PublicStoreService publicStoreService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Seller seller;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("DELETE FROM sale_form_image");
		jdbcTemplate.execute("DELETE FROM product_option");
		jdbcTemplate.execute("DELETE FROM product");
		jdbcTemplate.execute("DELETE FROM sale_form");
		jdbcTemplate.execute("DELETE FROM seller");

		seller = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-store-seller")
				.storeSlug(SLUG)
				.storeName("모음 상점")
				.shippingFee(3000)
				.freeShippingOver(50000)
				.representativeName("홍길동")
				.phone("010-0000-0000")
				.email("seller@moeum.store")
				.build());
		seller.approve();
		seller.updateProfile("모음 상점", "굿즈 선주문 전문", "https://instagram.com/moeum", null);
		sellerRepository.saveAndFlush(seller);
	}

	// ---------------------------------------------------------------- 헤더

	@Test
	@DisplayName("헤더에_상점_이름_소개_소셜_주소가_담긴다")
	void 헤더() {
		StorePageResponse.StoreSeller header = page().seller();

		assertThat(header.storeSlug()).isEqualTo(SLUG);
		assertThat(header.name()).isEqualTo("모음 상점");
		assertThat(header.bio()).isEqualTo("굿즈 선주문 전문");
		assertThat(header.socialUrl()).isEqualTo("https://instagram.com/moeum");
		assertThat(header.shippingFee()).isEqualTo(3000);
		assertThat(header.freeShippingOver()).isEqualTo(50000);
	}

	@Test
	@DisplayName("헤더에_대표자_실명과_연락처는_담기지_않는다")
	void 민감정보() {
		// 구매자에게 보이는 화면이다. 심사용으로 받은 값이 새면 안 된다
		String json = page().seller().toString();

		assertThat(json).doesNotContain("홍길동").doesNotContain("010-0000-0000")
				.doesNotContain("seller@moeum.store");
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("미발행_폼은_목록에_나오지_않는다")
	void 미발행_숨김() {
		saveForm("판매중 폼", SaleType.GROUP, SaleFormStatus.SELLING);
		saveForm("작성중 폼", SaleType.GROUP, SaleFormStatus.DRAFT);

		assertThat(titles()).containsExactly("판매중 폼");
	}

	@Test
	@DisplayName("마감된_폼도_보이되_판매중인_것이_위에_온다")
	void 정렬() {
		saveForm("마감 폼", SaleType.GROUP, SaleFormStatus.CLOSED);
		saveForm("판매중 폼", SaleType.GROUP, SaleFormStatus.SELLING);

		// 마감된 폼이 위에 쌓이면 지금 살 수 있는 것을 찾으려고 스크롤해야 한다
		assertThat(titles()).containsExactly("판매중 폼", "마감 폼");
	}

	@Test
	@DisplayName("승인되지_않은_셀러의_페이지는_없는_것으로_본다")
	void 미승인_셀러() {
		Seller pending = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-pending").storeSlug("pending-store").storeName("대기 상점")
				.shippingFee(3000).build());
		saveForm(pending, "폼", SaleType.GROUP, SaleFormStatus.SELLING);

		assertThatThrownBy(() -> publicStoreService.page("pending-store", null, null, 0, 20))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_NOT_FOUND);
	}

	@Test
	@DisplayName("없는_주소는_404다")
	void 없는_주소() {
		assertThatThrownBy(() -> publicStoreService.page("no-such-store", null, null, 0, 20))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 탭 · 검색

	@Test
	@DisplayName("판매_유형_탭으로_거른다")
	void 탭() {
		saveForm("공구 폼", SaleType.GROUP, SaleFormStatus.SELLING);
		saveForm("단독 폼", SaleType.SOLO, SaleFormStatus.SELLING);

		assertThat(titles(SaleType.GROUP, null)).containsExactly("공구 폼");
		assertThat(titles(SaleType.SOLO, null)).containsExactly("단독 폼");
		assertThat(titles(null, null)).hasSize(2);
	}

	@Test
	@DisplayName("상품명으로_검색한다")
	void 검색() {
		saveForm("아크릴 스탠드", SaleType.GROUP, SaleFormStatus.SELLING);
		saveForm("포토카드", SaleType.GROUP, SaleFormStatus.SELLING);

		assertThat(titles(null, "아크릴")).containsExactly("아크릴 스탠드");
		assertThat(titles(null, "스탠드")).containsExactly("아크릴 스탠드");
	}

	@Test
	@DisplayName("검색어의_와일드카드는_글자로_취급한다")
	void 와일드카드_이스케이프() {
		saveForm("아크릴 스탠드", SaleType.GROUP, SaleFormStatus.SELLING);

		// escape 하지 않으면 % 하나로 전체가 걸린다
        assertThat(titles(null, "%")).isEmpty();
		assertThat(titles(null, "_")).isEmpty();
	}

	@Test
	@DisplayName("검색과_탭은_같이_걸린다")
	void 검색과_탭() {
		saveForm("아크릴 스탠드", SaleType.GROUP, SaleFormStatus.SELLING);
		saveForm("아크릴 키링", SaleType.SOLO, SaleFormStatus.SELLING);

		assertThat(titles(SaleType.SOLO, "아크릴")).containsExactly("아크릴 키링");
	}

	// ---------------------------------------------------------------- 카드

	@Test
	@DisplayName("카드_가격은_가장_싼_옵션의_1차금_더하기_2차금이다")
	void 대표가() {
		SaleForm form = saveForm("아크릴 스탠드", SaleType.GROUP, SaleFormStatus.SELLING);
		addOption(form, "비싼 옵션", 25000, 10000);   // 35,000
		addOption(form, "싼 옵션", 20000, 12000);     // 32,000

		// 배송비는 들어가지 않는다 — 묶음당 1회라 상품 단위로 나눌 수 없다
		assertThat(items().get(0).price()).isEqualTo(32000);
	}

	@Test
	@DisplayName("공구_카드는_모집_현황을_단독_카드는_재고를_보여준다")
	void 카드_숫자() {
		SaleForm group = saveForm("공구 폼", SaleType.GROUP, SaleFormStatus.SELLING);
		jdbcTemplate.update("UPDATE sale_form SET target_qty = 100, sold = 68 WHERE id = ?", group.getId());
		saveForm("단독 폼", SaleType.SOLO, SaleFormStatus.SELLING);

		StorePageResponse.StoreItem groupCard = itemByTitle("공구 폼");
		StorePageResponse.StoreItem soloCard = itemByTitle("단독 폼");

		assertThat(groupCard.recruitedCount()).isEqualTo(68);
		assertThat(groupCard.recruitTarget()).isEqualTo(100);
		// SOLO 는 모집이라는 개념이 없다
		assertThat(soloCard.recruitedCount()).isNull();
		assertThat(soloCard.stock()).isEqualTo(10);
	}

	@Test
	@DisplayName("진행_현황을_감춘_폼은_모집_수를_주지_않는다")
	void 진행_비공개() {
		SaleForm form = saveForm("공구 폼", SaleType.GROUP, SaleFormStatus.SELLING);
		jdbcTemplate.update("UPDATE sale_form SET target_qty = 100, sold = 68, progress_public = 0 WHERE id = ?",
				form.getId());

		assertThat(items().get(0).recruitedCount()).isNull();
		assertThat(items().get(0).recruitTarget()).isNull();
	}

	@Test
	@DisplayName("마감이_지난_폼은_배치가_돌기_전에도_CLOSED_로_보인다")
	void 마감_직후() {
		SaleForm form = saveForm("곧 마감", SaleType.GROUP, SaleFormStatus.SELLING);
		jdbcTemplate.update("UPDATE sale_form SET closes_at = ? WHERE id = ?",
				LocalDateTime.now().minusMinutes(1), form.getId());

		// 마감 배치는 1분마다 돈다. 그 틈에 구매 버튼이 켜져 보이면 홀드에서 튕긴다
		assertThat(items().get(0).status()).isEqualTo(PublicStatus.CLOSED);
		assertThat(items().get(0).dDay()).isZero();
	}

	@Test
	@DisplayName("재고가_없으면_SOLD_OUT_이다")
	void 품절() {
		SaleForm form = saveForm("품절 폼", SaleType.SOLO, SaleFormStatus.SELLING);
		jdbcTemplate.update("UPDATE sale_form SET sold = stock_max WHERE id = ?", form.getId());

		assertThat(items().get(0).status()).isEqualTo(PublicStatus.SOLD_OUT);
		assertThat(items().get(0).stock()).isZero();
	}

	// ---------------------------------------------------------------- 페이지

	@Test
	@DisplayName("페이지_정보를_함께_준다")
	void 페이지() {
		for (int i = 0; i < 3; i++) {
			saveForm("폼 " + i, SaleType.GROUP, SaleFormStatus.SELLING);
		}

		StorePageResponse first = publicStoreService.page(SLUG, null, null, 0, 2);

		assertThat(first.items()).hasSize(2);
		assertThat(first.page().totalElements()).isEqualTo(3);
		assertThat(first.page().totalPages()).isEqualTo(2);
		// 무한 스크롤 판정을 프론트마다 다르게 계산하지 않게 서버가 준다
		assertThat(first.page().hasNext()).isTrue();
		assertThat(publicStoreService.page(SLUG, null, null, 1, 2).page().hasNext()).isFalse();
	}

	@Test
	@DisplayName("한_번에_가져갈_수_있는_수를_제한한다")
	void 크기_제한() {
		saveForm("폼", SaleType.GROUP, SaleFormStatus.SELLING);

		// 목록이 통째로 no-store 라 캐시가 없다. 무제한을 허용하면 그대로 DB 부하가 된다
		assertThat(publicStoreService.page(SLUG, null, null, 0, 10_000).page().size()).isEqualTo(50);
		assertThat(publicStoreService.page(SLUG, null, null, 0, 0).page().size()).isEqualTo(20);
	}

	// ---------------------------------------------------------------- 도우미

	private StorePageResponse page() {
		return publicStoreService.page(SLUG, null, null, 0, 20);
	}

	private List<StorePageResponse.StoreItem> items() {
		return page().items();
	}

	private StorePageResponse.StoreItem itemByTitle(String title) {
		return items().stream().filter(i -> i.title().equals(title)).findFirst().orElseThrow();
	}

	private List<String> titles() {
		return titles(null, null);
	}

	private List<String> titles(SaleType saleType, String q) {
		return publicStoreService.page(SLUG, saleType, q, 0, 20).items().stream()
				.map(StorePageResponse.StoreItem::title).toList();
	}

	private SaleForm saveForm(String title, SaleType type, SaleFormStatus status) {
		return saveForm(seller, title, type, status);
	}

	private SaleForm saveForm(Seller owner, String title, SaleType type, SaleFormStatus status) {
		long unique = System.nanoTime();
		SaleForm form = SaleForm.builder()
				.seller(owner)
				.title(title)
				.slug("form-" + unique)
				.saleType(type)
				.stockMax(10)
				.targetQty(type == SaleType.GROUP ? 5 : null)
				.closesAt(LocalDateTime.now().plusDays(5))
				.minOrderAmount(0)
				.build();
		Product product = Product.builder().name("상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?", status.name(), form.getId());
		return form;
	}

	/**
	 * 옵션을 하나 더 붙인다.
	 *
	 * status 를 다시 찍는 이유가 있다 — 폼 엔티티는 생성자에서 DRAFT 로 시작하는데,
	 * 여기서 flush 하면 그 값이 jdbcTemplate 으로 바꿔 둔 SELLING 을 덮어쓴다.
	 * 상태 전이 API 를 거치지 않고 SQL 로 상태를 만든 대가다.
	 */
	private void addOption(SaleForm form, String name, int deposit1, int deposit2) {
		Product product = form.getProducts().get(0);
		product.addOption(ProductOption.builder()
				.name(name).deposit1Amount(deposit1).deposit2Amount(deposit2).sortOrder(1).build());
		saleFormRepository.saveAndFlush(form);
		jdbcTemplate.update("UPDATE sale_form SET status = 'SELLING' WHERE id = ?", form.getId());
	}
}
