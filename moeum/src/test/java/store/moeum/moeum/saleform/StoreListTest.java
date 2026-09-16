package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.saleform.dto.StoreListResponse;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 리스트 — 구매자가 상점을 훑는 화면.
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>미승인 셀러가 새지 않는다</b> — 실리면 눌렀을 때 셀러 페이지가 404 로 떨어진다</li>
 *   <li><b>심사용 개인정보가 새지 않는다</b> — 대표자 실명 · 연락처 · 이메일은 응답 어디에도 없다</li>
 *   <li>검색이 화면에 찍히는 이름을 본다 — 상점 이름이 없으면 storeSlug 로도 찾힌다</li>
 *   <li>검색어의 % 와 _ 가 와일드카드로 동작하지 않는다</li>
 * </ul>
 */
class StoreListTest extends IntegrationTest {

	@Autowired
	private PublicStoreService publicStoreService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("DELETE FROM sale_form_image");
		jdbcTemplate.execute("DELETE FROM product_option");
		jdbcTemplate.execute("DELETE FROM product");
		jdbcTemplate.execute("DELETE FROM sale_form");
		jdbcTemplate.execute("DELETE FROM seller");
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("승인된_셀러만_목록에_나온다")
	void 승인_필터() {
		approved("moeum-store", "모음 상점");
		pending("not-yet", "심사 중 상점");
		rejected("rejected-store", "반려된 상점");

		// 실리면 카드를 눌렀을 때 셀러 페이지가 404 로 떨어진다
		assertThat(slugs()).containsExactly("moeum-store");
	}

	@Test
	@DisplayName("상품이_없는_셀러도_목록에_나온다")
	void 상품_없는_셀러() {
		approved("empty-store", "빈 상점");

		// 폼을 전부 마감한 셀러가 사라지면 단골이 찾아갈 길이 없다
		assertThat(slugs()).containsExactly("empty-store");
	}

	@Test
	@DisplayName("심사용_개인정보는_응답에_담기지_않는다")
	void 개인정보() throws Exception {
		Seller seller = approved("moeum-store", "모음 상점");
		assertThat(seller.getRepresentativeName()).isEqualTo("홍길동");

		// 필드를 하나 더할 때 실수로 실리면 여기서 걸린다
		String body = mockMvc.perform(get("/stores"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body)
				.doesNotContain("홍길동")
				.doesNotContain("010-0000-0000")
				.doesNotContain("seller@moeum.store")
				.doesNotContain("representativeName")
				.doesNotContain("settlementAccount")
				.doesNotContain("businessNo");
	}

	// ---------------------------------------------------------------- 카드

	@Test
	@DisplayName("카드에_이름과_한_줄_소개가_담긴다")
	void 카드() {
		Seller seller = approved("moeum-store", "모음 상점");
		seller.updateProfile("모음 상점", "굿즈 선주문 전문", "https://instagram.com/moeum",
				null, "010-9999-0000");
		sellerRepository.saveAndFlush(seller);

		assertThat(items()).singleElement().satisfies(item -> {
			assertThat(item.storeSlug()).isEqualTo("moeum-store");
			assertThat(item.name()).isEqualTo("모음 상점");
			assertThat(item.bio()).isEqualTo("굿즈 선주문 전문");
		});
	}

	@Test
	@DisplayName("상점_이름이_없으면_storeSlug_가_카드에_나온다")
	void 이름_대체() {
		approved("no-name-store", null);

		// 대표자 실명으로 대체하지 않는다. 이름이 비었다고 개인정보를 공개할 이유가 없다
		assertThat(items().get(0).name()).isEqualTo("no-name-store");
	}

	@Test
	@DisplayName("최근에_승인된_셀러가_위에_온다")
	void 정렬() {
		approved("older", "예전 상점");
		jdbcTemplate.update("UPDATE seller SET approved_at = DATE_SUB(NOW(6), INTERVAL 3 DAY)"
				+ " WHERE store_slug = ?", "older");
		approved("newer", "새 상점");

		assertThat(slugs()).containsExactly("newer", "older");
	}

	// ---------------------------------------------------------------- 검색

	@Test
	@DisplayName("셀러_이름으로_부분_검색한다")
	void 검색() {
		approved("moeum-store", "모음 상점");
		approved("other-store", "다른 상점");

		assertThat(slugs("모음")).containsExactly("moeum-store");
	}

	@Test
	@DisplayName("상점_이름이_없는_셀러는_storeSlug_로_찾힌다")
	void 검색_이름_대체() {
		approved("handmade-goods", null);

		// 카드에 뻔히 보이는 이름으로 쳤는데 안 나오면 없는 상점으로 보인다
		assertThat(slugs("handmade")).containsExactly("handmade-goods");
	}

	@Test
	@DisplayName("검색어의_퍼센트와_언더바는_글자로_취급한다")
	void 검색_와일드카드() {
		approved("moeum-store", "모음 상점");
		approved("percent-store", "100% 수제");

		// escape 하지 않으면 '%' 한 글자가 전체를 긁어 온다
		assertThat(slugs("%")).containsExactly("percent-store");
		assertThat(slugs("_")).isEmpty();
	}

	@Test
	@DisplayName("검색어가_비면_전체를_준다")
	void 검색_공백() {
		approved("moeum-store", "모음 상점");
		approved("other-store", "다른 상점");

		assertThat(slugs("   ")).hasSize(2);
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("로그인_없이_볼_수_있고_캐시하지_않는다")
	void 비로그인() throws Exception {
		approved("moeum-store", "모음 상점");

		// 셀러가 프로필을 고쳤는데 옛날 값이 남아 있으면 저장이 안 된 줄 안다
		mockMvc.perform(get("/stores"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].storeSlug").value("moeum-store"))
				.andExpect(jsonPath("$.page.totalElements").value(1));
	}

	@Test
	@DisplayName("페이지_크기는_50_을_넘지_못한다")
	void 페이지_크기() {
		approved("moeum-store", "모음 상점");

		assertThat(publicStoreService.list(null, 0, 999).page().size()).isEqualTo(50);
		assertThat(publicStoreService.list(null, 0, 0).page().size()).isEqualTo(20);
	}

	// ---------------------------------------------------------------- 도우미

	private List<StoreListResponse.StoreListItem> items() {
		return publicStoreService.list(null, 0, 20).items();
	}

	private List<String> slugs() {
		return slugs(null);
	}

	private List<String> slugs(String q) {
		return publicStoreService.list(q, 0, 20).items().stream()
				.map(StoreListResponse.StoreListItem::storeSlug)
				.toList();
	}

	private Seller approved(String slug, String storeName) {
		Seller seller = save(slug, storeName);
		seller.approve();
		return sellerRepository.saveAndFlush(seller);
	}

	private void pending(String slug, String storeName) {
		save(slug, storeName);
	}

	private void rejected(String slug, String storeName) {
		Seller seller = save(slug, storeName);
		seller.reject();
		sellerRepository.saveAndFlush(seller);
	}

	private Seller save(String slug, String storeName) {
		return sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-" + slug)
				.storeSlug(slug)
				.storeName(storeName)
				.shippingFee(3000)
				.representativeName("홍길동")
				.phone("010-0000-0000")
				.email("seller@moeum.store")
				.build());
	}
}
