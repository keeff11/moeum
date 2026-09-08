package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 폼 상세.
 *
 * 배송비는 폼이 아니라 셀러가 갖는다(스키마 v3 — 한 셀러 주문은 배송비 1회).
 * 그래서 옵션마다 붙이지 않고 폼 수준에 한 번만 실어 보낸다.
 */
public record SaleFormDetailResponse(
		@Schema(description = "판매 폼 id", example = "12")
		Long id,

		@Schema(description = "상품명", example = "아크릴 스탠드")
		String title,

		@Schema(description = "판매 폼 주소", example = "acrylic-stand-2nd")
		String slug,

		@Schema(description = "판매 유형. GROUP=공동구매, SOLO=단독 판매")
		SaleType saleType,

		@Schema(description = "판매 상태. DRAFT=작성 중(구매자에게 안 보임) · SELLING=판매 중 "
				+ "· PAUSED=일시중지 · CLOSED=마감 · ENDED=종료")
		SaleFormStatus status,

		@Schema(description = "판매할 총 수량", example = "100")
		int stockMax,

		@Schema(description = "결제 중이라 잡혀 있는 수량. 15분 안에 결제되지 않으면 풀린다", example = "3")
		int held,

		@Schema(description = "결제가 끝난 수량", example = "68")
		int sold,

		@Schema(description = "지금 팔 수 있는 수량", example = "29")
		int remainingStock,

		@Schema(description = "목표 수량. 단독 판매면 null", example = "100")
		Integer targetQty,

		@Schema(description = "1인당 구매 상한. 제한이 없으면 null", example = "2")
		Integer maxPerUser,

		@Schema(description = "판매 시작 시각")
		LocalDateTime opensAt,

		@Schema(description = "모집 마감 시각")
		LocalDateTime closesAt,

		@Schema(description = "마감을 연장한 횟수", example = "0")
		int extendedCount,

		@Schema(description = "목표 미달 시 처리. CANCEL=자동 취소, PROCEED=그대로 진행, EXTEND=연장(미구현)")
		ShortfallPolicy shortfallPolicy,

		@Schema(description = "발송 시작 안내 문구", example = "8월 20일(월) 순차발송")
		String shipStartText,

		@Schema(description = "최소 주문 금액. 0이면 제한 없음", example = "10000")
		int minOrderAmount,

		@Schema(description = "상품 상세 설명. Lexical 에디터 JSON 문자열")
		String descriptionJson,

		@Schema(description = "모집 현황을 구매자에게 공개하는가", example = "true")
		boolean progressPublic,

		@Schema(description = "노출 순서대로의 이미지 주소. 저장된 키를 읽기용 주소로 바꿔 준다")
		List<String> images,

		@Schema(description = "배송비. 묶음당 1회이고 2차금에서 청구된다", example = "3000")
		int shippingFee,

		@Schema(description = "이 금액 이상이면 배송비 면제. 설정하지 않았으면 null", example = "50000")
		Integer freeShippingOver,

		@Schema(description = "상품과 옵션 목록")
		List<ProductResponse> products,

		@Schema(description = "만든 시각")
		LocalDateTime createdAt,

		@Schema(description = "마지막으로 수정한 시각")
		LocalDateTime updatedAt
) {

	@Schema(description = "상품 하나와 그 옵션들")
	public record ProductResponse(
			@Schema(description = "상품 id", example = "5") Long id,
			@Schema(description = "상품 이름", example = "아크릴 스탠드") String name,
			@Schema(description = "노출 순서. 작을수록 먼저", example = "0") int sortOrder,
			@Schema(description = "옵션 목록") List<OptionResponse> options) {

		static ProductResponse from(Product product) {
			return new ProductResponse(
					product.getId(),
					product.getName(),
					product.getSortOrder(),
					product.getOptions().stream().map(OptionResponse::from).toList()
			);
		}
	}

	@Schema(description = "옵션 하나. 가격은 절대값이다 — 기준가에 더하는 추가금이 아니다")
	public record OptionResponse(

			@Schema(description = "옵션 id", example = "31")
			Long id,

			@Schema(description = "옵션명", example = "블루")
			String name,

			@Schema(description = "1차금. 주문할 때 바로 결제하는 금액", example = "20000")
			int deposit1Amount,

			@Schema(description = "2차금. 입고 후 청구되는 잔금", example = "12000")
			int deposit2Amount,

			@Schema(description = "옵션 총액(1차금+2차금). 배송비는 셀러 단위라 들어 있지 않다",
					example = "32000")
			int optionAmount,

			@Schema(description = "노출 순서. 작을수록 먼저", example = "0")
			int sortOrder
	) {

		static OptionResponse from(ProductOption option) {
			return new OptionResponse(
					option.getId(),
					option.getName(),
					option.getDeposit1Amount(),
					option.getDeposit2Amount(),
					option.totalAmount(),
					option.getSortOrder()
			);
		}
	}

	public static SaleFormDetailResponse of(SaleForm form, Seller seller, List<String> imageUrls) {
		return new SaleFormDetailResponse(
				form.getId(),
				form.getTitle(),
				form.getSlug(),
				form.getSaleType(),
				form.getStatus(),
				form.getStockMax(),
				form.getHeld(),
				form.getSold(),
				form.remainingStock(),
				form.getTargetQty(),
				form.getMaxPerUser(),
				form.getOpensAt(),
				form.getClosesAt(),
				form.getExtendedCount(),
				form.getShortfallPolicy(),
				form.getShipStartText(),
				form.getMinOrderAmount(),
				form.getDescriptionJson(),
				form.isProgressPublic(),
				imageUrls,
				seller.getShippingFee(),
				seller.getFreeShippingOver(),
				form.getProducts().stream().map(ProductResponse::from).toList(),
				form.getCreatedAt(),
				form.getUpdatedAt()
		);
	}
}
