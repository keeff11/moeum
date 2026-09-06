package store.moeum.moeum.seller.dto;

import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;

/**
 * 셀러 응답. 사업자번호 · 정산계좌는 담지 않는다.
 * 심사·정산 담당자만 볼 값이라 조회 API 로 흘려보낼 이유가 없다.
 */
public record SellerResponse(
		Long id,
		String storeSlug,
		String storeName,
		ReviewStatus reviewStatus,
		String representativeName,
		String phone,
		String email,
		int shippingFee,
		Integer freeShippingOver,
		LocalDateTime approvedAt,
		LocalDateTime createdAt
) {

	public static SellerResponse from(Seller seller) {
		return new SellerResponse(
				seller.getId(),
				seller.getStoreSlug(),
				seller.getStoreName(),
				seller.getReviewStatus(),
				seller.getRepresentativeName(),
				seller.getPhone(),
				seller.getEmail(),
				seller.getShippingFee(),
				seller.getFreeShippingOver(),
				seller.getApprovedAt(),
				seller.getCreatedAt()
		);
	}
}
