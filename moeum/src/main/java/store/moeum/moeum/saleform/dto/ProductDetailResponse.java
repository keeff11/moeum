package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
		@Schema(description = "판매 폼 id. 주문·찜에서 쓰는 그 id 다", example = "12")
		Long id,

		@Schema(description = "판매 유형. GROUP=공동구매, SOLO=단독 판매. 화면 분기 키다")
		SaleType saleType,

		@Schema(description = "상품명", example = "아크릴 스탠드")
		String title,

		@Schema(description = "노출 순서대로의 이미지 주소. 첫 번째가 대표 이미지다")
		List<String> images,

		@Schema(description = "셀러 정보. 헤더와 배송비 계산에 쓴다")
		SellerResponse seller,

		@Schema(description = "최소 주문 금액. 이 금액을 넘겨야 주문할 수 있다", example = "10000")
		int minOrderAmount,

		@Schema(description = "발송 시작 안내 문구. 서버가 문장으로 만들어 준다",
				example = "8월 20일(월) 순차발송")
		String shippingStartText,

		@Schema(description = "모집 마감 시각. 마감이 없으면 null")
		LocalDateTime recruitDeadline,
		/** D-5 의 5. 마감이 없으면 null, 이미 지났으면 0 */
		@Schema(description = "마감까지 남은 일수(D-5 의 5). 마감이 없으면 null, 지났으면 0", example = "5")
		Integer recruitDDay,
		/** GROUP 만. 진행 현황을 감춘 폼은 null */
		@Schema(description = "모집 목표 수량(68/100 의 100). 공동구매가 아니거나 감췄으면 null",
				example = "100")
		Integer recruitTarget,

		@Schema(description = "1인당 구매 상한. 제한이 없으면 null", example = "2")
		Integer maxPerUser,

		@Schema(description = "상품 상세 설명. Lexical 에디터 JSON 문자열이다")
		String description,

		@Schema(description = "구매 버튼 활성 여부. 재고는 이 응답에 없다 — /availability 로 따로 받는다")
		PublicStatus status,

		@Schema(description = "상품과 옵션 목록. 옵션 선택 화면이 이걸 쓴다")
		List<ProductResponse> products
) {

	/** 공개해도 되는 셀러 정보만. 대표자 실명 · 연락처 · 사업자번호는 담지 않는다 */
	@Schema(description = "공개해도 되는 셀러 정보만. 대표자 실명·연락처는 담기지 않는다")
	public record SellerResponse(
			@Schema(description = "셀러 id", example = "1") Long id,
			@Schema(description = "상점 이름", example = "모음 상점") String name,
			@Schema(description = "배송비. 묶음당 1회이고 2차금에서 청구된다", example = "3000") int shippingFee,
			@Schema(description = "이 금액 이상이면 배송비 면제. 없으면 null", example = "50000")
			Integer freeShippingOver) {

		static SellerResponse from(Seller seller) {
			return new SellerResponse(
					seller.getId(),
					seller.displayName(),
					seller.getShippingFee(),
					seller.getFreeShippingOver()
			);
		}
	}

	@Schema(description = "상품 하나와 그 옵션들")
	public record ProductResponse(
			@Schema(description = "상품 id", example = "5") Long id,
			@Schema(description = "상품명", example = "아크릴 스탠드") String name,
			@Schema(description = "선택할 수 있는 옵션 목록") List<OptionResponse> options) {

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
	@Schema(description = "옵션 하나. 가격은 절대값이다 — 기준가에 더하는 추가금이 아니다")
	public record OptionResponse(

			@Schema(description = "옵션 id. 장바구니·주문에 이 값을 보낸다", example = "31")
			Long id,

			@Schema(description = "옵션명", example = "블루") String name,

			@Schema(description = "1차금. 주문할 때 바로 결제하는 금액", example = "20000")
			int deposit1Amount,

			@Schema(description = "2차금. 입고 후에 청구되는 잔금", example = "12000")
			int deposit2Amount,

			@Schema(description = "옵션 총액(1차금+2차금). 배송비는 들어 있지 않다", example = "32000")
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
