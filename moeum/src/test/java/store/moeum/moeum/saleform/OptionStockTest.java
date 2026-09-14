package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.ProductAvailabilityResponse;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.saleform.dto.SaleFormHistoryResponse;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 옵션별 재고 (D-054) — 폼 생성 · 옵션 재고 수정 · 구매자 재고 조회.
 *
 * 홀드 · 확정 · 되돌림이 옵션 카운터를 움직이는지는 order.OptionStockHoldTest 가 본다.
 */
class OptionStockTest extends IntegrationTest {

	private static final String KAKAO_ID = "kakao-option-stock";

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private PublicProductService publicProductService;

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		// 다른 클래스가 남긴 주문이 옵션을 참조한다. FK 순서대로 전부 지운다
		fixture.clean();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId(KAKAO_ID).storeSlug("option-stock-store").shippingFee(3000).build());
		sellerService.approve(seller.getId());
	}

	// ---------------------------------------------------------------- 생성

	@Test
	@DisplayName("옵션마다_재고를_넣으면_폼_재고는_그_합이다")
	void 합계() {
		Long id = saleFormService.create(KAKAO_ID, solo(null, option("A", 100), option("B", 200)));

		SaleFormDetailResponse detail = saleFormService.findMineDetail(KAKAO_ID, id);
		assertThat(detail.stockMax()).isEqualTo(300);
		assertThat(detail.optionStock()).isTrue();
		assertThat(detail.products().get(0).options())
				.extracting(SaleFormDetailResponse.OptionResponse::stock)
				.containsExactly(100, 200);
		assertThat(detail.products().get(0).options())
				.extracting(SaleFormDetailResponse.OptionResponse::remainingStock)
				.containsExactly(100, 200);
	}

	@Test
	@DisplayName("옵션_재고가_있으면_보낸_stockMax_는_무시된다")
	void stockMax_무시() {
		Long id = saleFormService.create(KAKAO_ID, solo(999, option("A", 10), option("B", 20)));

		assertThat(saleFormService.findMineDetail(KAKAO_ID, id).stockMax()).isEqualTo(30);
	}

	@Test
	@DisplayName("옵션_재고는_전부_넣거나_전부_비워야_한다")
	void 반만_넣으면_거부() {
		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, solo(100, option("A", 10), option("B", null))))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("모든 옵션")
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_SALE_FORM);
	}

	@Test
	@DisplayName("옵션_재고도_stockMax_도_없으면_거부한다")
	void 재고_없음_거부() {
		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, solo(null, option("A", null), option("B", null))))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("stockMax");
	}

	@Test
	@DisplayName("옵션_재고가_없는_폼은_이전처럼_stockMax_를_쓴다")
	void 폼_재고_모드() {
		Long id = saleFormService.create(KAKAO_ID, solo(50, option("A", null), option("B", null)));

		SaleFormDetailResponse detail = saleFormService.findMineDetail(KAKAO_ID, id);
		assertThat(detail.stockMax()).isEqualTo(50);
		assertThat(detail.optionStock()).isFalse();
		assertThat(detail.products().get(0).options())
				.allSatisfy(option -> {
					assertThat(option.stock()).isNull();
					assertThat(option.remainingStock()).isNull();
				});
	}

	@Test
	@DisplayName("공동구매_목표수량은_옵션_재고_합계와_비교한다")
	void 공구_목표수량() {
		SaleFormCreateRequest tooBig = new SaleFormCreateRequest(
				"공구", "group-" + System.nanoTime(), SaleType.GROUP, null, 50, null,
				null, java.time.LocalDateTime.now().plusDays(7), null, null, 0, null, null, true, List.of(),
				List.of(new SaleFormCreateRequest.ProductRequest("상품", 0, List.of(
						new SaleFormCreateRequest.OptionRequest("A", 20000, 12000, 10, 0),
						new SaleFormCreateRequest.OptionRequest("B", 20000, 12000, 20, 1)))));

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, tooBig))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("목표수량이 재고보다");
	}

	// ---------------------------------------------------------------- 옵션 재고 수정

	@Test
	@DisplayName("옵션_재고를_바꾸면_폼_재고가_합계로_따라오고_둘_다_이력에_남는다")
	void 옵션_재고_수정() {
		Long id = saleFormService.create(KAKAO_ID, solo(null, option("A", 100), option("B", 200)));
		Long optionA = optionIdOf(id, "A");

		SaleFormDetailResponse updated = saleFormService.updateOptionStock(KAKAO_ID, id, optionA, 150);

		assertThat(updated.stockMax()).isEqualTo(350);
		assertThat(updated.products().get(0).options())
				.extracting(SaleFormDetailResponse.OptionResponse::stock)
				.containsExactly(150, 200);

		List<SaleFormHistoryResponse> history = saleFormService.findHistory(KAKAO_ID, id);
		assertThat(history).extracting(SaleFormHistoryResponse::field)
				.containsExactlyInAnyOrder("optionStock:" + optionA, "stockMax");
	}

	@Test
	@DisplayName("이미_나간_수량보다_적게_줄일_수_없다")
	void 옵션_재고_하한() {
		Long id = saleFormService.create(KAKAO_ID, solo(null, option("A", 100), option("B", 200)));
		Long optionA = optionIdOf(id, "A");
		jdbcTemplate.update("UPDATE product_option SET held = 3, sold = 4 WHERE id = ?", optionA);

		assertThatThrownBy(() -> saleFormService.updateOptionStock(KAKAO_ID, id, optionA, 6))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("7개");

		assertThat(saleFormService.updateOptionStock(KAKAO_ID, id, optionA, 7).stockMax()).isEqualTo(207);
	}

	@Test
	@DisplayName("옵션_재고를_쓰지_않는_폼은_옵션_재고_수정을_거부한다")
	void 폼_재고_모드_거부() {
		Long id = saleFormService.create(KAKAO_ID, solo(50, option("A", null), option("B", null)));

		assertThatThrownBy(() -> saleFormService.updateOptionStock(KAKAO_ID, id, optionIdOf(id, "A"), 10))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("옵션 재고를 쓰지 않는");
	}

	@Test
	@DisplayName("옵션_재고_폼의_수정은_보낸_stockMax_를_보지_않는다")
	void 폼_수정은_합계_유지() {
		Long id = saleFormService.create(KAKAO_ID, solo(null, option("A", 100), option("B", 200)));

		SaleFormDetailResponse updated = saleFormService.update(KAKAO_ID, id, new SaleFormUpdate(
				"바뀐 제목", 5, null, null, null, null, null, null, 0, null, null, true, List.of()));

		assertThat(updated.stockMax()).isEqualTo(300);
	}

	@Test
	@DisplayName("폼_재고_모드의_수정은_stockMax_가_없으면_거부한다")
	void 폼_수정_stockMax_필수() {
		Long id = saleFormService.create(KAKAO_ID, solo(50, option("A", null), option("B", null)));

		assertThatThrownBy(() -> saleFormService.update(KAKAO_ID, id, new SaleFormUpdate(
				"바뀐 제목", null, null, null, null, null, null, null, 0, null, null, true, List.of())))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("stockMax");
	}

	// ---------------------------------------------------------------- 구매자 재고 조회

	@Test
	@DisplayName("구매자_재고_조회는_옵션별_수량을_준다")
	void 구매자_옵션_재고() {
		Long id = saleFormService.create(KAKAO_ID, solo(null, option("A", 100), option("B", 200)));
		saleFormService.startSelling(KAKAO_ID, id);
		Long optionA = optionIdOf(id, "A");
		jdbcTemplate.update("UPDATE product_option SET held = 30, sold = 65 WHERE id = ?", optionA);
		jdbcTemplate.update("UPDATE sale_form SET held = 30, sold = 65 WHERE id = ?", id);

		ProductAvailabilityResponse availability = publicProductService.availability(id);

		assertThat(availability.stock()).isEqualTo(205);
		assertThat(availability.options())
				.extracting(ProductAvailabilityResponse.OptionStock::stock)
				.containsExactly(5, 200);
	}

	@Test
	@DisplayName("옵션_재고가_없으면_옵션별_수량은_폼_재고와_같다")
	void 구매자_폼_재고() {
		Long id = saleFormService.create(KAKAO_ID, solo(50, option("A", null), option("B", null)));
		saleFormService.startSelling(KAKAO_ID, id);
		jdbcTemplate.update("UPDATE sale_form SET sold = 10 WHERE id = ?", id);

		ProductAvailabilityResponse availability = publicProductService.availability(id);

		assertThat(availability.stock()).isEqualTo(40);
		assertThat(availability.options())
				.extracting(ProductAvailabilityResponse.OptionStock::stock)
				.containsExactly(40, 40);
	}

	// ---------------------------------------------------------------- 픽스처

	private static SaleFormCreateRequest.OptionRequest option(String name, Integer stock) {
		return new SaleFormCreateRequest.OptionRequest(name, 32000, 0, stock, 0);
	}

	private static SaleFormCreateRequest solo(Integer stockMax, SaleFormCreateRequest.OptionRequest... options) {
		return new SaleFormCreateRequest(
				"단독 판매", "solo-" + System.nanoTime(), SaleType.SOLO, stockMax, null, null,
				null, null, null, null, 0, null, null, true, List.of(),
				List.of(new SaleFormCreateRequest.ProductRequest("상품", 0, List.of(options))));
	}

	private Long optionIdOf(Long saleFormId, String name) {
		return jdbcTemplate.queryForObject("""
				SELECT o.id FROM product_option o
				  JOIN product p ON p.id = o.product_id
				 WHERE p.sale_form_id = ? AND o.name = ?
				""", Long.class, saleFormId, name);
	}
}
