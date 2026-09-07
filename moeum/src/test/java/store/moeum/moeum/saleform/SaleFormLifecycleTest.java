package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.saleform.dto.SaleFormHistoryResponse;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판매 폼 상태 흐름 — 시작 · 일시중지 · 마감.
 *
 * <b>이게 없으면 셀러가 상품을 열 방법이 없다.</b> 생성 직후는 DRAFT 이고
 * 공개 API 는 DRAFT 를 404 로 숨기므로, 만들어둔 상품·결제 API 에 태울 폼이 생기지 않는다.
 *
 * 되돌릴 수 없는 전이(CLOSED)와 되돌릴 수 있는 전이(PAUSED)를 나누는 것이 요점이다.
 */
class SaleFormLifecycleTest extends IntegrationTest {

	private static final String KAKAO_ID = "kakao-lifecycle";

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private PublicProductService publicProductService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId(KAKAO_ID).storeSlug("lifecycle-store").storeName("라이프사이클 상점")
				.shippingFee(3000).build());
		sellerService.approve(seller.getId());
	}

	@Test
	@DisplayName("생성_직후는_DRAFT_라_구매자에게_보이지_않는다")
	void 생성_직후는_DRAFT다() {
		Long formId = createForm();

		assertThat(statusOf(formId)).isEqualTo(SaleFormStatus.DRAFT);
		// 공개 조회는 DRAFT 를 404 로 숨긴다 (D-021)
		assertThatThrownBy(() -> publicProductService.detail(formId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	@Test
	@DisplayName("판매를_시작하면_구매자에게_보인다")
	void 판매_시작() {
		Long formId = createForm();

		SaleFormDetailResponse response = saleFormService.startSelling(KAKAO_ID, formId);

		assertThat(response.status()).isEqualTo(SaleFormStatus.SELLING);
		assertThat(publicProductService.detail(formId).title()).isEqualTo("겨울 공동구매");
		assertThat(publicProductService.availability(formId).stock()).isEqualTo(100);
	}

	@Test
	@DisplayName("상태_변경도_이력에_남는다")
	void 상태_변경_이력() {
		Long formId = createForm();
		saleFormService.startSelling(KAKAO_ID, formId);
		saleFormService.pause(KAKAO_ID, formId);

		List<SaleFormHistoryResponse> history = saleFormService.findHistory(KAKAO_ID, formId);

		assertThat(history).extracting(SaleFormHistoryResponse::field).contains("status");
		assertThat(history).extracting(SaleFormHistoryResponse::newValue)
				.contains("SELLING", "PAUSED");
	}

	@Test
	@DisplayName("이미_판매_중이면_다시_시작해도_이력이_쌓이지_않는다")
	void 중복_시작은_이력을_남기지_않는다() {
		Long formId = createForm();
		saleFormService.startSelling(KAKAO_ID, formId);
		int before = saleFormService.findHistory(KAKAO_ID, formId).size();

		saleFormService.startSelling(KAKAO_ID, formId);

		assertThat(statusOf(formId)).isEqualTo(SaleFormStatus.SELLING);
		assertThat(saleFormService.findHistory(KAKAO_ID, formId)).hasSize(before);
	}

	@Test
	@DisplayName("일시중지하면_구매_버튼이_꺼지고_다시_열_수_있다")
	void 일시중지와_재개() {
		Long formId = createForm();
		saleFormService.startSelling(KAKAO_ID, formId);

		saleFormService.pause(KAKAO_ID, formId);
		assertThat(publicProductService.availability(formId).status().name()).isEqualTo("PAUSED");

		saleFormService.startSelling(KAKAO_ID, formId);
		assertThat(publicProductService.availability(formId).status().name()).isEqualTo("SELLING");
	}

	@Test
	@DisplayName("마감하면_되돌릴_수_없다")
	void 마감은_되돌릴_수_없다() {
		Long formId = createForm();
		saleFormService.startSelling(KAKAO_ID, formId);
		saleFormService.close(KAKAO_ID, formId);

		assertThat(statusOf(formId)).isEqualTo(SaleFormStatus.CLOSED);
		// 마감된 공구에는 이미 결제가 걸려 있다. 다시 열면 정산·발주 기준이 어긋난다
		assertThatThrownBy(() -> saleFormService.startSelling(KAKAO_ID, formId))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("다시 열 수 없습니다");
	}

	@Test
	@DisplayName("마감일시가_지났으면_열_수_없다")
	void 지난_마감일시로는_못_연다() {
		Long formId = createForm();
		// 수정으로 마감일이 과거가 된 상황
		jdbcTemplate.update("UPDATE sale_form SET closes_at = DATE_SUB(NOW(6), INTERVAL 1 DAY) WHERE id = ?", formId);

		// 그냥 열면 마감 배치가 1분 안에 도로 닫는다. 셀러 눈엔 "열었는데 안 열린다" 로 보인다
		assertThatThrownBy(() -> saleFormService.startSelling(KAKAO_ID, formId))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("마감일시가 이미 지났습니다");

		assertThat(statusOf(formId)).isEqualTo(SaleFormStatus.DRAFT);
	}

	@Test
	@DisplayName("DRAFT_는_마감할_수_없다")
	void DRAFT_는_마감_불가() {
		Long formId = createForm();

		assertThatThrownBy(() -> saleFormService.close(KAKAO_ID, formId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_SALE_FORM);
	}

	@Test
	@DisplayName("판매_중이_아니면_일시중지할_수_없다")
	void DRAFT_는_일시중지_불가() {
		Long formId = createForm();

		assertThatThrownBy(() -> saleFormService.pause(KAKAO_ID, formId))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("판매 중인 폼만");
	}

	@Test
	@DisplayName("남의_폼은_열_수_없다")
	void 남의_폼() {
		Long formId = createForm();
		Seller other = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-other-seller").storeSlug("other-store").storeName("남의 상점")
				.shippingFee(3000).build());
		sellerService.approve(other.getId());

		assertThatThrownBy(() -> saleFormService.startSelling("kakao-other-seller", formId))
				.isInstanceOf(BusinessException.class);
	}

	// ---------------------------------------------------------------- 픽스처

	private SaleFormStatus statusOf(Long formId) {
		return saleFormRepository.findById(formId).orElseThrow().getStatus();
	}

	private Long createForm() {
		return saleFormService.create(KAKAO_ID, new SaleFormCreateRequest(
				"겨울 공동구매", "winter-" + System.nanoTime(), SaleType.GROUP, 100, 30, 2,
				null, LocalDateTime.now().plusDays(7), ShortfallPolicy.CANCEL,
				"8월 20일(월) 순차발송", 10000, null, true,
				List.of("sale-forms/1/a.jpg"),
				List.of(new SaleFormCreateRequest.ProductRequest("머플러", 0,
						List.of(new SaleFormCreateRequest.OptionRequest("옵션 A", 20000, 12000, 0))))));
	}
}
