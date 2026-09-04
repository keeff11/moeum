package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormHistoryRepository;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.saleform.dto.SaleFormHistoryResponse;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판매 폼 수정과 sale_form_history 기록 */
class SaleFormUpdateTest extends IntegrationTest {

	private static final String KAKAO_ID = "kakao-update-test";
	private static final LocalDateTime CLOSES_AT = LocalDateTime.of(2026, 12, 24, 23, 59);

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private SaleFormHistoryRepository historyRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long sellerId;
	private Long formId;

	@BeforeEach
	void setUp() {
		historyRepository.deleteAll();
		saleFormRepository.deleteAll();
		sellerRepository.deleteAll();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId(KAKAO_ID).storeSlug("moeum-store").shippingFee(3000).build());
		sellerService.approve(seller.getId());
		this.sellerId = seller.getId();

		this.formId = saleFormService.create(KAKAO_ID, new SaleFormCreateRequest(
				"겨울 공동구매", "winter-form", SaleType.GROUP, 100, 30, 2,
				null, CLOSES_AT, ShortfallPolicy.CANCEL, "8월 20일(월) 순차발송", 10000,
				null, true,
				List.of(new SaleFormCreateRequest.ProductRequest("머플러", 0,
						List.of(new SaleFormCreateRequest.OptionRequest("옵션 A", 20000, 12000, 0))))));
	}

	@Test
	@DisplayName("수정하면_값이_바뀌고_바뀐_필드마다_이력이_남는다")
	void 수정하면_값이_바뀌고_바뀐_필드마다_이력이_남는다() {
		SaleFormDetailResponse updated = saleFormService.update(KAKAO_ID, formId,
				command().title("겨울 공동구매 (연장)").targetQty(50).build());

		assertThat(updated.title()).isEqualTo("겨울 공동구매 (연장)");
		assertThat(updated.targetQty()).isEqualTo(50);

		List<SaleFormHistoryResponse> history = saleFormService.findHistory(KAKAO_ID, formId);
		assertThat(history).hasSize(2);
		assertThat(history).extracting(SaleFormHistoryResponse::field)
				.containsExactlyInAnyOrder("title", "targetQty");
		assertThat(history).allSatisfy(row -> assertThat(row.changedBy()).isEqualTo(sellerId));

		SaleFormHistoryResponse targetQty = history.stream()
				.filter(row -> row.field().equals("targetQty")).findFirst().orElseThrow();
		assertThat(targetQty.oldValue()).isEqualTo("30");
		assertThat(targetQty.newValue()).isEqualTo("50");
	}

	@Test
	@DisplayName("값이_그대로면_이력을_남기지_않는다")
	void 값이_그대로면_이력을_남기지_않는다() {
		saleFormService.update(KAKAO_ID, formId, command().build());

		assertThat(saleFormService.findHistory(KAKAO_ID, formId)).isEmpty();
	}

	@Test
	@DisplayName("null로_바꾸는_것도_이력에_남는다")
	void null로_바꾸는_것도_이력에_남는다() {
		saleFormService.update(KAKAO_ID, formId, command().shipStartText(null).build());

		List<SaleFormHistoryResponse> history = saleFormService.findHistory(KAKAO_ID, formId);
		assertThat(history).hasSize(1);
		assertThat(history.get(0).field()).isEqualTo("shipStartText");
		assertThat(history.get(0).oldValue()).isEqualTo("8월 20일(월) 순차발송");
		assertThat(history.get(0).newValue()).isNull();
	}

	@Test
	@DisplayName("이미_선점_판매된_수량보다_적게_재고를_줄일_수_없다")
	void 이미_선점_판매된_수량보다_적게_재고를_줄일_수_없다() {
		// 3단계의 조건부 UPDATE 가 재고를 잡은 상황
		jdbcTemplate.update("UPDATE sale_form SET held = 4, sold = 6 WHERE id = ?", formId);

		// 목표수량도 함께 줄인다 — 안 그러면 "목표수량 > 재고" 규칙에 먼저 걸린다
		assertThatThrownBy(() -> saleFormService.update(KAKAO_ID, formId,
				command().stockMax(9).targetQty(9).build()))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("10개")
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.INVALID_SALE_FORM);

		// 딱 맞게 줄이는 건 된다
		SaleFormDetailResponse updated = saleFormService.update(KAKAO_ID, formId,
				command().stockMax(10).targetQty(10).build());
		assertThat(updated.stockMax()).isEqualTo(10);
	}

	@Test
	@DisplayName("GROUP은_수정에서도_목표수량과_마감일시가_필수다")
	void GROUP은_수정에서도_목표수량과_마감일시가_필수다() {
		assertThatThrownBy(() -> saleFormService.update(KAKAO_ID, formId, command().targetQty(null).build()))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("목표수량");

		assertThatThrownBy(() -> saleFormService.update(KAKAO_ID, formId, command().closesAt(null).build()))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("마감일시");
	}

	@Test
	@DisplayName("검증에_걸리면_아무것도_바뀌지_않고_이력도_남지_않는다")
	void 검증에_걸리면_아무것도_바뀌지_않고_이력도_남지_않는다() {
		assertThatThrownBy(() -> saleFormService.update(KAKAO_ID, formId,
				command().title("바뀐 제목").targetQty(999).build()))
				.isInstanceOf(BusinessException.class);

		assertThat(saleFormService.findMineDetail(KAKAO_ID, formId).title()).isEqualTo("겨울 공동구매");
		assertThat(saleFormService.findHistory(KAKAO_ID, formId)).isEmpty();
	}

	@Test
	@DisplayName("남의_판매_폼은_수정할_수_없다")
	void 남의_판매_폼은_수정할_수_없다() {
		Seller other = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-other").storeSlug("other-store").build());
		sellerService.approve(other.getId());

		assertThatThrownBy(() -> saleFormService.update("kakao-other", formId, command().build()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	@Test
	@DisplayName("이력_조회도_남의_폼이면_거부한다")
	void 이력_조회도_남의_폼이면_거부한다() {
		Seller other = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-other2").storeSlug("other-store2").build());
		sellerService.approve(other.getId());

		assertThatThrownBy(() -> saleFormService.findHistory("kakao-other2", formId))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	private Builder command() {
		return new Builder();
	}

	/** 기본값은 생성 당시와 같다. 테스트마다 한 항목만 바꿔 보낸다 */
	private static final class Builder {
		private String title = "겨울 공동구매";
		private int stockMax = 100;
		private Integer targetQty = 30;
		private LocalDateTime closesAt = CLOSES_AT;
		private String shipStartText = "8월 20일(월) 순차발송";

		Builder title(String value) {
			this.title = value;
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

		Builder shipStartText(String value) {
			this.shipStartText = value;
			return this;
		}

		SaleFormUpdate build() {
			return new SaleFormUpdate(title, stockMax, targetQty, 2, null, closesAt,
					ShortfallPolicy.CANCEL, shipStartText, 10000, null, true);
		}
	}
}
