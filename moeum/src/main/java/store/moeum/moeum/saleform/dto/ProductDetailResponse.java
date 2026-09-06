package store.moeum.moeum.saleform.dto;

import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 구매자용 상품 정적 정보 (api-spec 2절, B1 · B1-O · B2 · B5).
 *
 * 셀러용 {@link SaleFormDetailResponse} 와 나눈 이유는 담기는 값이 다르기 때문이다.
 * held · sold · stockMax · slug · 심사 상태처럼 내부 사정을 드러내는 값은 여기 오지 않는다.
 * 재고 숫자도 여기 없다 — 캐시되는 응답에 휘발성 값을 섞으면 낡은 재고가 화면에 남는다.
 * 그건 /availability 가 no-store 로 따로 내려준다.
 *
 * {@code id} 는 판매 폼 id 다. 재고 · 목표수량 · 마감이 전부 폼 단위라 상품 페이지의 주체가 폼이다.
 * 경로가 /products 인 것은 구매자 화면의 용어를 따른 것이다.
 */
public record ProductDetailResponse(
		Long id,
		SaleType saleType,
		String title,
		List<String> images,
		SellerResponse seller,
		int minOrderAmount,
		String shippingStartText,
		LocalDateTime recruitDeadline,
		/** D-5 의 5. 마감이 없으면 null, 이미 지났으면 0 */
		Integer recruitDDay,
		/** GROUP 만. 진행 현황을 감춘 폼은 null */
		Integer recruitTarget,
		Integer maxPerUser,
		String description,
		PublicStatus status,
		List<ProductResponse> products
) {

	/** 공개해도 되는 셀러 정보만. 대표자 실명 · 연락처 · 사업자번호는 담지 않는다 */
	public record SellerResponse(Long id, String name, int shippingFee, Integer freeShippingOver) {

		static SellerResponse from(Seller seller) {
			return new SellerResponse(
					seller.getId(),
					seller.displayName(),
					seller.getShippingFee(),
					seller.getFreeShippingOver()
			);
		}
	}

	public record ProductResponse(Long id, String name, List<OptionResponse> options) {

		static ProductResponse from(Product product) {
			return new ProductResponse(
					product.getId(),
					product.getName(),
					product.getOptions().stream().map(OptionResponse::from).toList()
			);
		}
	}

	/**
	 * 옵션 가격은 절대값이다 (기준가 + 추가금이 아니다).
	 * 배송비는 옵션이 아니라 셀러가 갖는다 — 옵션마다 실어 보내면 수량만큼 붙는 것으로 오해된다.
	 */
	public record OptionResponse(
			Long id,
			String name,
			int deposit1Amount,
			int deposit2Amount,
			int optionAmount
	) {

		static OptionResponse from(ProductOption option) {
			return new OptionResponse(
					option.getId(),
					option.getName(),
					option.getDeposit1Amount(),
					option.getDeposit2Amount(),
					option.totalAmount()
			);
		}
	}

	/**
	 * @param imageUrls 서비스가 {@code ImageStorage} 로 조립해 넘긴 읽기용 주소.
	 *                  엔티티에는 S3 키만 있고, 여기서 키를 만지지 않는다
	 */
	public static ProductDetailResponse of(SaleForm form, LocalDateTime now, List<String> imageUrls) {
		boolean group = form.getSaleType() == SaleType.GROUP;

		return new ProductDetailResponse(
				form.getId(),
				form.getSaleType(),
				form.getTitle(),
				imageUrls,
				SellerResponse.from(form.getSeller()),
				form.getMinOrderAmount(),
				form.getShipStartText(),
				form.getClosesAt(),
				dDay(form.getClosesAt(), now),
				(group && form.isProgressPublic()) ? form.getTargetQty() : null,
				form.getMaxPerUser(),
				form.getDescriptionJson(),
				PublicStatus.of(form, now),
				form.getProducts().stream().map(ProductResponse::from).toList()
		);
	}

	/** 남은 일수. 서버가 계산해 내려준다 — 클라이언트 시계를 믿고 D-day 를 그리면 사람마다 달라진다 */
	private static Integer dDay(LocalDateTime closesAt, LocalDateTime now) {
		if (closesAt == null) {
			return null;
		}
		long days = Duration.between(now, closesAt).toDays();
		return (days < 0) ? 0 : (int) days;
	}
}
