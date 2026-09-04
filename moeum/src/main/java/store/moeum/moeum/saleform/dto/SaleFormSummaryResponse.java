package store.moeum.moeum.saleform.dto;

import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDateTime;

/** 판매 폼 목록 한 줄. 상품 · 옵션은 담지 않는다 */
public record SaleFormSummaryResponse(
		Long id,
		String title,
		String slug,
		SaleType saleType,
		SaleFormStatus status,
		int stockMax,
		int held,
		int sold,
		int remainingStock,
		Integer targetQty,
		LocalDateTime opensAt,
		LocalDateTime closesAt,
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
