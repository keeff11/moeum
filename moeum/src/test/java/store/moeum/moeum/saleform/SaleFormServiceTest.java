package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판매 유형별 규칙 — GROUP 은 목표수량·마감 필수, SOLO 는 목표수량 무시 + 2차금 금지 */
class SaleFormServiceTest extends IntegrationTest {

	private static final String KAKAO_ID = "kakao-saleform-test";

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	private Long sellerId;

	@BeforeEach
	void setUp() {
		saleFormRepository.deleteAll();
		sellerRepository.deleteAll();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId(KAKAO_ID)
				.storeSlug("moeum-store")
				.shippingFee(3000)
				.freeShippingOver(50000)
				.businessNo("1234567890")
				.settlementAccount("국민 123456-78-901234")
				.representativeName("홍길동")
				.phone("010-0000-0000")
				.email("seller@example.com")
				.build());
		sellerService.approve(seller.getId());
		this.sellerId = seller.getId();
	}

	@Test
	@DisplayName("GROUP은_목표수량이_없으면_거부한다")
	void GROUP은_목표수량이_없으면_거부한다() {
		SaleFormCreateRequest request = group()
				.targetQty(null)
				.build();

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, request))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("목표수량")
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_SALE_FORM);
	}

	@Test
	@DisplayName("GROUP은_마감일시가_없으면_거부한다")
	void GROUP은_마감일시가_없으면_거부한다() {
		SaleFormCreateRequest request = group()
				.closesAt(null)
				.build();

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, request))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("마감일시");
	}

	@Test
	@DisplayName("GROUP은_목표수량과_마감일시가_있으면_생성된다")
	void GROUP은_목표수량과_마감일시가_있으면_생성된다() {
		Long id = saleFormService.create(KAKAO_ID, group().build());

		SaleFormDetailResponse detail = saleFormService.findMineDetail(KAKAO_ID, id);
		assertThat(detail.saleType()).isEqualTo(SaleType.GROUP);
		assertThat(detail.targetQty()).isEqualTo(30);
		assertThat(detail.closesAt()).isNotNull();
		assertThat(detail.shortfallPolicy()).isEqualTo(ShortfallPolicy.CANCEL);
	}

	@Test
	@DisplayName("SOLO는_targetQty를_보내도_무시하고_null로_저장한다")
	void SOLO는_targetQty를_보내도_무시하고_null로_저장한다() {
		SaleFormCreateRequest request = solo()
				.targetQty(50)
				.shortfallPolicy(ShortfallPolicy.EXTEND)
				.build();

		Long id = saleFormService.create(KAKAO_ID, request);

		SaleFormDetailResponse detail = saleFormService.findMineDetail(KAKAO_ID, id);
		assertThat(detail.saleType()).isEqualTo(SaleType.SOLO);
		assertThat(detail.targetQty()).isNull();
		// 미달 정책도 목표수량이 있어야 의미가 있으므로 함께 비운다
		assertThat(detail.shortfallPolicy()).isNull();
	}

	@Test
	@DisplayName("SOLO는_deposit2Amount가_0이_아니면_거부한다")
	void SOLO는_deposit2Amount가_0이_아니면_거부한다() {
		SaleFormCreateRequest request = solo()
				.options(List.of(option("옵션 A", 32000, 5000)))
				.build();

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, request))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("2차금")
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_SALE_FORM);
	}

	@Test
	@DisplayName("SOLO는_deposit2Amount가_전부_0이면_생성된다")
	void SOLO는_deposit2Amount가_전부_0이면_생성된다() {
		Long id = saleFormService.create(KAKAO_ID, solo().build());

		SaleFormDetailResponse detail = saleFormService.findMineDetail(KAKAO_ID, id);
		assertThat(detail.products()).hasSize(1);
		assertThat(detail.products().get(0).options())
				.allSatisfy(option -> assertThat(option.deposit2Amount()).isZero());
	}

	@Test
	@DisplayName("GROUP은_목표수량이_재고보다_크면_거부한다")
	void GROUP은_목표수량이_재고보다_크면_거부한다() {
		SaleFormCreateRequest request = group()
				.stockMax(10)
				.targetQty(30)
				.build();

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, request))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("재고");
	}

	@Test
	@DisplayName("심사가_승인되지_않은_셀러는_판매_폼을_만들_수_없다")
	void 심사가_승인되지_않은_셀러는_판매_폼을_만들_수_없다() {
		sellerService.reject(sellerId);

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, group().build()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_NOT_APPROVED);
	}

	@Test
	@DisplayName("같은_셀러가_같은_슬러그를_두_번_쓰면_거부한다")
	void 같은_셀러가_같은_슬러그를_두_번_쓰면_거부한다() {
		saleFormService.create(KAKAO_ID, group().build());

		assertThatThrownBy(() -> saleFormService.create(KAKAO_ID, group().build()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.DUPLICATE_SALE_FORM_SLUG);
	}

	@Test
	@DisplayName("남의_판매_폼_상세는_조회되지_않는다")
	void 남의_판매_폼_상세는_조회되지_않는다() {
		Long id = saleFormService.create(KAKAO_ID, group().build());

		Seller other = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-other").storeSlug("other-store").build());
		sellerService.approve(other.getId());

		assertThatThrownBy(() -> saleFormService.findMineDetail("kakao-other", id))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	@Test
	@DisplayName("목록은_내_판매_폼만_최신순으로_준다")
	void 목록은_내_판매_폼만_최신순으로_준다() {
		saleFormService.create(KAKAO_ID, group().slug("form-a").build());
		saleFormService.create(KAKAO_ID, group().slug("form-b").build());

		var list = saleFormService.findMine(KAKAO_ID);

		assertThat(list).hasSize(2);
		assertThat(list.get(0).slug()).isEqualTo("form-b");
		assertThat(list).allSatisfy(form -> assertThat(form.remainingStock()).isEqualTo(100));
	}

	// --- 요청 빌더 ---

	private Builder group() {
		return new Builder()
				.saleType(SaleType.GROUP)
				.targetQty(30)
				.closesAt(LocalDateTime.now().plusDays(7))
				.shortfallPolicy(ShortfallPolicy.CANCEL)
				.options(List.of(option("옵션 A", 20000, 12000)));
	}

	private Builder solo() {
		return new Builder()
				.saleType(SaleType.SOLO)
				.options(List.of(option("옵션 A", 32000, 0)));
	}

	private static SaleFormCreateRequest.OptionRequest option(String name, int deposit1, int deposit2) {
		return new SaleFormCreateRequest.OptionRequest(name, deposit1, deposit2, 0);
	}

	/** 테스트마다 한 항목만 바꿔 보내려고 둔 빌더 */
	private static final class Builder {
		private String slug = "winter-form";
		private SaleType saleType = SaleType.GROUP;
		private int stockMax = 100;
		private Integer targetQty;
		private LocalDateTime closesAt;
		private ShortfallPolicy shortfallPolicy;
		private List<SaleFormCreateRequest.OptionRequest> options = List.of();

		Builder slug(String value) {
			this.slug = value;
			return this;
		}

		Builder saleType(SaleType value) {
			this.saleType = value;
			return this;
		}

		Builder stockMax(int value) {
			this.stockMax = value;
			return this;
		}

		Builder targetQty(Integer value) {
			this.targetQty = value;
			return this;
		}

		Builder closesAt(LocalDateTime value) {
			this.closesAt = value;
			return this;
		}

		Builder shortfallPolicy(ShortfallPolicy value) {
			this.shortfallPolicy = value;
			return this;
		}

		Builder options(List<SaleFormCreateRequest.OptionRequest> value) {
			this.options = value;
			return this;
		}

		SaleFormCreateRequest build() {
			return new SaleFormCreateRequest(
					"겨울 공동구매", slug, saleType, stockMax, targetQty, 2,
					null, closesAt, shortfallPolicy, "8월 20일(월) 순차발송", 10000,
					null, true, List.of("https://cdn.example.com/1.jpg"),
					List.of(new SaleFormCreateRequest.ProductRequest("머플러", 0, options))
			);
		}
	}
}
