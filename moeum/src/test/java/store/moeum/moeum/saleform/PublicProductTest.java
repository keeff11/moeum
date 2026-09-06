package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.ProductAvailabilityResponse;
import store.moeum.moeum.saleform.dto.ProductDetailResponse;
import store.moeum.moeum.saleform.dto.PublicStatus;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구매자용 공개 상품 조회 (GET /products/{id}, /availability).
 *
 * 여기서 지키려는 것은 셋이다.
 *  - 로그인 없이 열리되, 미발행 폼과 셀러 개인정보는 새어 나가지 않는다
 *  - 모집 수는 확정 주문 기준이다. 홀드는 세지 않는다
 *  - 마감 · 품절이 화면에 제때 반영된다 (배치가 늦어도)
 */
class PublicProductTest extends IntegrationTest {

	@Autowired
	private PublicProductService publicProductService;

	@Autowired
	private SaleFormCloseBatch saleFormCloseBatch;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;
	private Seller seller;

	@BeforeEach
	void setUp() {
		fixture.clean();
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

		seller = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-public-test")
				.storeSlug("moeum-store")
				.storeName("모으미 상점")
				.shippingFee(3000)
				.freeShippingOver(50000)
				.representativeName("홍길동")
				.build());
		seller.approve();
		sellerRepository.saveAndFlush(seller);
	}

	@Test
	@DisplayName("공개_상세는_상호명을_주고_대표자_실명은_주지_않는다")
	void 공개_상세는_대표자_실명을_주지_않는다() {
		Long formId = sellingForm(100, 30, true, LocalDateTime.now().plusDays(5));

		ProductDetailResponse response = publicProductService.detail(formId);

		assertThat(response.seller().name()).isEqualTo("모으미 상점");
		assertThat(response.seller().name()).isNotEqualTo("홍길동");
		assertThat(response.images()).containsExactly(
				"https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg");
		assertThat(response.status()).isEqualTo(PublicStatus.SELLING);
		assertThat(response.recruitTarget()).isEqualTo(30);
		assertThat(response.recruitDDay()).isEqualTo(4);
		assertThat(response.products()).hasSize(1);
		assertThat(response.products().get(0).options()).hasSize(1);
	}

	@Test
	@DisplayName("상호명이_비면_대표자명이_아니라_store_slug로_대체한다")
	void 상호명이_비면_slug로_대체한다() {
		jdbcTemplate.update("UPDATE seller SET store_name = NULL WHERE id = ?", seller.getId());
		Long formId = sellingForm(100, 30, true, LocalDateTime.now().plusDays(5));

		ProductDetailResponse response = publicProductService.detail(formId);

		assertThat(response.seller().name()).isEqualTo("moeum-store");
	}

	@Test
	@DisplayName("미발행_DRAFT_폼은_404다")
	void 미발행_폼은_404다() {
		Long formId = form(100, 30, true, LocalDateTime.now().plusDays(5), SaleFormStatus.DRAFT);

		// 403 이면 "그 id 에 폼이 있다"는 사실이 새어 나가 준비 중인 상품을 훑어낼 수 있다
		assertThatThrownBy(() -> publicProductService.detail(formId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);

		assertThatThrownBy(() -> publicProductService.availability(formId))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("모집_수는_확정_주문만_세고_홀드는_세지_않는다")
	void 모집_수는_홀드를_세지_않는다() {
		Long formId = sellingForm(10, 30, true, LocalDateTime.now().plusDays(5));
		jdbcTemplate.update("UPDATE sale_form SET held = 2, sold = 3 WHERE id = ?", formId);

		ProductAvailabilityResponse response = publicProductService.availability(formId);

		// 홀드는 15분 뒤 만료될 수 있다. 세면 모집 숫자가 뒤로 간다
		assertThat(response.recruitedCount()).isEqualTo(3);
		assertThat(response.recruitTarget()).isEqualTo(30);
		assertThat(response.stock()).isEqualTo(5);
		assertThat(response.status()).isEqualTo(PublicStatus.SELLING);
	}

	@Test
	@DisplayName("진행_현황을_감추면_모집_수를_내려주지_않는다")
	void 진행_현황을_감추면_모집_수가_없다() {
		Long formId = sellingForm(10, 30, false, LocalDateTime.now().plusDays(5));
		jdbcTemplate.update("UPDATE sale_form SET sold = 3 WHERE id = ?", formId);

		ProductAvailabilityResponse availability = publicProductService.availability(formId);
		ProductDetailResponse detail = publicProductService.detail(formId);

		assertThat(availability.recruitedCount()).isNull();
		assertThat(availability.recruitTarget()).isNull();
		assertThat(detail.recruitTarget()).isNull();
		// 재고는 감추지 않는다 — 스티퍼 상한을 그릴 수 없다
		assertThat(availability.stock()).isEqualTo(7);
	}

	@Test
	@DisplayName("SOLO_는_모집_개념이_없어_모집_수가_비어_있다")
	void SOLO_는_모집_수가_없다() {
		Long formId = soloForm(10);

		ProductAvailabilityResponse response = publicProductService.availability(formId);

		assertThat(response.recruitedCount()).isNull();
		assertThat(response.recruitTarget()).isNull();
		assertThat(response.stock()).isEqualTo(10);
	}

	@Test
	@DisplayName("재고가_0이면_SOLD_OUT_이다")
	void 재고가_0이면_SOLD_OUT() {
		Long formId = sellingForm(5, 30, true, LocalDateTime.now().plusDays(5));
		jdbcTemplate.update("UPDATE sale_form SET sold = 5 WHERE id = ?", formId);

		assertThat(publicProductService.availability(formId).status()).isEqualTo(PublicStatus.SOLD_OUT);
		assertThat(publicProductService.detail(formId).status()).isEqualTo(PublicStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("마감_시각이_지나면_배치가_돌기_전에도_CLOSED_로_보인다")
	void 마감시각이_지나면_배치_전에도_CLOSED다() {
		// DB status 는 아직 SELLING 이다. 배치는 1분마다 돌기 때문에 그 틈이 반드시 생긴다
		Long formId = sellingForm(10, 30, true, LocalDateTime.now().minusMinutes(1));

		assertThat(saleFormRepository.findById(formId).orElseThrow().getStatus())
				.isEqualTo(SaleFormStatus.SELLING);
		assertThat(publicProductService.detail(formId).status()).isEqualTo(PublicStatus.CLOSED);
		assertThat(publicProductService.availability(formId).status()).isEqualTo(PublicStatus.CLOSED);
	}

	@Test
	@DisplayName("D_day는_마감이_지났으면_0이고_마감이_없으면_null이다")
	void D_day_계산() {
		Long past = sellingForm(10, 30, true, LocalDateTime.now().minusDays(2));
		Long none = soloForm(10);

		assertThat(publicProductService.detail(past).recruitDDay()).isZero();
		assertThat(publicProductService.detail(none).recruitDDay()).isNull();
	}

	@Test
	@DisplayName("availability_응답은_캐시하지_않는다")
	void availability_는_no_store_다() throws Exception {
		Long formId = sellingForm(10, 30, true, LocalDateTime.now().plusDays(5));

		// 캐시된 재고를 보고 구매를 누르면 홀드에서 품절로 튕긴다
		mockMvc.perform(get("/products/{id}/availability", formId))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.stock").value(10));
	}

	@Test
	@DisplayName("로그인_없이_조회된다")
	void 로그인_없이_조회된다() throws Exception {
		Long formId = sellingForm(10, 30, true, LocalDateTime.now().plusDays(5));

		mockMvc.perform(get("/products/{id}", formId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.seller.name").value("모으미 상점"))
				.andExpect(jsonPath("$.title").value("겨울 공동구매"));
	}

	@Test
	@DisplayName("마감_배치는_지난_폼만_CLOSED_로_넘긴다")
	void 마감_배치는_지난_폼만_넘긴다() {
		Long expired = sellingForm(10, 30, true, LocalDateTime.now().minusMinutes(1));
		Long alive = sellingForm(10, 30, true, LocalDateTime.now().plusDays(3));
		Long noDeadline = soloForm(10);

		int closed = saleFormCloseBatch.closeOnce();

		assertThat(closed).isEqualTo(1);
		assertThat(statusOf(expired)).isEqualTo(SaleFormStatus.CLOSED);
		assertThat(statusOf(alive)).isEqualTo(SaleFormStatus.SELLING);
		assertThat(statusOf(noDeadline)).isEqualTo(SaleFormStatus.SELLING);
	}

	@Test
	@DisplayName("마감_배치를_두_번_돌려도_같은_폼을_다시_세지_않는다")
	void 마감_배치는_멱등이다() {
		sellingForm(10, 30, true, LocalDateTime.now().minusMinutes(1));

		assertThat(saleFormCloseBatch.closeOnce()).isEqualTo(1);
		assertThat(saleFormCloseBatch.closeOnce()).isZero();
	}

	// ---------------------------------------------------------------- 픽스처

	private SaleFormStatus statusOf(Long formId) {
		return SaleFormStatus.valueOf(jdbcTemplate.queryForObject(
				"SELECT status FROM sale_form WHERE id = ?", String.class, formId));
	}

	private Long sellingForm(int stockMax, Integer targetQty, boolean progressPublic, LocalDateTime closesAt) {
		return form(stockMax, targetQty, progressPublic, closesAt, SaleFormStatus.SELLING);
	}

	private Long soloForm(int stockMax) {
		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("단독 판매")
				.slug("solo-" + System.nanoTime())
				.saleType(SaleType.SOLO)
				.stockMax(stockMax)
				.minOrderAmount(0)
				.build();
		form.addProduct(productWithOption());
		saleFormRepository.saveAndFlush(form);
		publish(form.getId(), SaleFormStatus.SELLING);
		return form.getId();
	}

	private Long form(int stockMax, Integer targetQty, boolean progressPublic,
	                  LocalDateTime closesAt, SaleFormStatus status) {
		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("겨울 공동구매")
				.slug("form-" + System.nanoTime())
				.saleType(SaleType.GROUP)
				.stockMax(stockMax)
				.targetQty(targetQty)
				.maxPerUser(2)
				.closesAt(closesAt)
				.shipStartText("8월 20일(월) 순차발송")
				.minOrderAmount(10000)
				.progressPublic(progressPublic)
				.build();
		form.addProduct(productWithOption());
		form.replaceImages(List.of("https://cdn.example.com/1.jpg", "https://cdn.example.com/2.jpg"));
		saleFormRepository.saveAndFlush(form);
		publish(form.getId(), status);
		return form.getId();
	}

	private static Product productWithOption() {
		Product product = Product.builder().name("머플러").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		return product;
	}

	/** 생성 직후는 항상 DRAFT 다. 판매 시작 흐름이 아직 없어 여기서 직접 올린다 */
	private void publish(Long formId, SaleFormStatus status) {
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?", status.name(), formId);
	}
}
