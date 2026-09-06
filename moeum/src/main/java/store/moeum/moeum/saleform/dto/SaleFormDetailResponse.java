package store.moeum.moeum.saleform.dto;

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
		Integer maxPerUser,
		LocalDateTime opensAt,
		LocalDateTime closesAt,
		int extendedCount,
		ShortfallPolicy shortfallPolicy,
		String shipStartText,
		int minOrderAmount,
		String descriptionJson,
		boolean progressPublic,
		List<String> images,
		int shippingFee,
		Integer freeShippingOver,
		List<ProductResponse> products,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
) {

	public record ProductResponse(Long id, String name, int sortOrder, List<OptionResponse> options) {

		static ProductResponse from(Product product) {
			return new ProductResponse(
					product.getId(),
					product.getName(),
					product.getSortOrder(),
					product.getOptions().stream().map(OptionResponse::from).toList()
			);
		}
	}

	public record OptionResponse(
			Long id,
			String name,
			int deposit1Amount,
			int deposit2Amount,
			/** 상품 총액 = 1차금 + 2차금. 배송비는 셀러 단위라 포함하지 않는다 */
			int optionAmount,
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
