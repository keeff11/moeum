package store.moeum.moeum.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;

/**
 * 셀러 응답. 사업자번호 · 정산계좌는 담지 않는다.
 * 심사·정산 담당자만 볼 값이라 조회 API 로 흘려보낼 이유가 없다.
 */
public record SellerResponse(
		@Schema(description = "셀러 id", example = "1")
		Long id,

		@Schema(description = "판매공간 주소. meoum.store/{이 값}", example = "moeum-store")
		String storeSlug,

		@Schema(description = "상점 이름", example = "모음 상점")
		String storeName,
		/** 셀러 페이지 헤더용 공개 프로필 (V7) */
		@Schema(description = "한 줄 소개", example = "굿즈 선주문 전문")
		String bio,

		@Schema(description = "소셜 주소", example = "https://instagram.com/moeum")
		String socialUrl,

		@Schema(description = "프로필 이미지 주소. 등록하지 않았으면 null")
		String profileImageUrl,
		/** 구매자에게 공개되는 문의처. 아래 phone 은 심사·정산용이라 별개다 */
		@Schema(description = "구매자에게 공개되는 문의 연락처", example = "010-9999-0000")
		String publicContact,

		@Schema(description = "심사 상태. APPROVED 여야 판매를 시작할 수 있다")
		ReviewStatus reviewStatus,
		@Schema(description = "대표자 실명. 심사용이라 구매자에게는 나가지 않는다")
		String representativeName,

		@Schema(description = "심사·정산용 연락처. 구매자에게는 나가지 않는다 — 공개용은 publicContact 다")
		String phone,

		@Schema(description = "심사·정산용 이메일. 구매자에게는 나가지 않는다")
		String email,

		@Schema(description = "배송비. 묶음당 1회이고 2차금에서 청구된다", example = "3000")
		int shippingFee,

		@Schema(description = "이 금액 이상이면 배송비 면제. 설정하지 않았으면 null", example = "50000")
		Integer freeShippingOver,

		@Schema(description = "심사가 승인된 시각. 아직이면 null")
		LocalDateTime approvedAt,

		@Schema(description = "온보딩을 제출한 시각")
		LocalDateTime createdAt
) {

	public static SellerResponse from(Seller seller) {
		return from(seller, null);
	}

	/** @param profileImageUrl 저장된 키를 ImageStorage 가 조립한 주소. 없으면 null */
	public static SellerResponse from(Seller seller, String profileImageUrl) {
		return new SellerResponse(
				seller.getId(),
				seller.getStoreSlug(),
				seller.getStoreName(),
				seller.getBio(),
				seller.getSocialUrl(),
				profileImageUrl,
				seller.getPublicContact(),
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
