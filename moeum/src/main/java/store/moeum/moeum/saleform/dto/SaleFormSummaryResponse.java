package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDateTime;

/** 판매 폼 목록 한 줄. 상품 · 옵션은 담지 않는다 */
public record SaleFormSummaryResponse(
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

		@Schema(description = "결제 중이라 잡혀 있는 수량. 15분 안에 결제되지 않으면 풀린다",
				example = "3")
		int held,

		@Schema(description = "결제가 끝난 수량. 모집 현황의 분자가 이 값이다", example = "68")
		int sold,

		@Schema(description = "지금 팔 수 있는 수량(총 수량 - 잡힌 것 - 팔린 것)", example = "29")
		int remainingStock,

		@Schema(description = "목표 수량. 단독 판매면 null", example = "100")
		Integer targetQty,

		@Schema(description = "판매 시작 시각")
		LocalDateTime opensAt,

		@Schema(description = "모집 마감 시각")
		LocalDateTime closesAt,

		@Schema(description = "만든 시각")
		LocalDateTime createdAt
) {

	public static SaleFormSummaryResponse from(SaleForm form) {
		return new SaleFormSummaryResponse(
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
				form.getOpensAt(),
				form.getClosesAt(),
				form.getCreatedAt()
		);
	}
}
