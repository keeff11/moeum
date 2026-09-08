package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 페이지 (B0) — 헤더 + 판매 폼 목록.
 *
 * <b>이 응답은 캐시하지 않는다.</b> 카드마다 "모집 68/100" 과 "재고 5개" 를 보여줘야 하는데
 * 그건 초 단위로 바뀌는 값이다. 상세({@link ProductDetailResponse})는 정적 정보와
 * {@link ProductAvailabilityResponse} 를 나눴지만, 목록에서 같은 방식을 쓰면
 * 카드 수만큼 조회가 나간다. 목록은 통째로 no-store 로 내린다 (D-029).
 *
 * 찜(하트)은 여기 없다. 구매자마다 다른 값이라 넣는 순간 이 응답이 사용자별로 갈린다 —
 * 프론트가 별도로 받아 합친다.
 */
@Schema(description = "셀러 페이지 — 상단 프로필과 판매 상품 목록을 한 번에 준다")
public record StorePageResponse(

		@Schema(description = "페이지 상단 셀러 프로필")
		StoreSeller seller,

		@Schema(description = "상품 카드 목록. 판매 중인 것이 먼저, 그다음 최신순")
		List<StoreItem> items,

		@Schema(description = "페이지 정보. 무한 스크롤에 쓴다")
		PageInfo page
) {

	/**
	 * 공개해도 되는 셀러 정보만.
	 * <b>대표자 실명 · 연락처 · 이메일 · 사업자번호 · 정산계좌는 담지 않는다.</b>
	 */
	@Schema(description = "셀러 프로필. 심사용으로 받은 대표자 실명·연락처·사업자번호는 담기지 않는다")
	public record StoreSeller(

			@Schema(description = "셀러 id", example = "1")
			Long id,

			@Schema(description = "판매공간 주소. meoum.store/{이 값} 으로 접근한다", example = "moeum-store")
			String storeSlug,

			@Schema(description = "화면에 표시되는 상점 이름. 비어 있으면 storeSlug 가 대신 나온다",
					example = "모음 상점")
			String name,

			@Schema(description = "한 줄 소개. 등록하지 않았으면 null", example = "굿즈 선주문 전문")
			String bio,

			@Schema(description = "인스타그램 등 소셜 주소. 헤더 버튼에 링크로 걸린다",
					example = "https://instagram.com/moeum")
			String socialUrl,

			@Schema(description = "프로필 이미지 주소. 등록하지 않았으면 null — 프론트가 기본 이미지를 넣는다")
			String profileImageUrl,

			@Schema(description = "구매자 문의 연락처. 전화번호·이메일·오픈채팅 링크 중 셀러가 적은 값이 그대로 온다. "
					+ "심사용 연락처와는 다른 값이라 등록하지 않았으면 null",
					example = "010-9999-0000")
			String publicContact,

			@Schema(description = "배송비. 주문 묶음당 1회이고 2차금에서 청구된다", example = "3000")
			int shippingFee,

			@Schema(description = "이 금액 이상이면 배송비 면제. 설정하지 않았으면 null", example = "50000")
			Integer freeShippingOver
	) {
	}

	/**
	 * 목록 카드 한 장.
	 *
	 * @param price          카드에 찍히는 대표가 — <b>가장 싼 옵션의 1차금 + 2차금</b>이다.
	 *                       배송비는 들어 있지 않다 (묶음당 1회라 상품 단위로 나눌 수 없다)
	 * @param dDay           D-5 의 5. 마감이 없으면 null, 이미 지났으면 0
	 * @param recruitedCount 모집 68/100 의 분자. 확정 주문 수량이고 홀드는 세지 않는다.
	 *                       GROUP 이 아니거나 셀러가 진행 현황을 감췄으면 null
	 * @param stock          재고 5개. 카드에는 SOLO 에서 쓴다
	 */
	@Schema(description = "상품 카드 한 장")
	public record StoreItem(

			@Schema(description = "판매 폼 id. GET /products/{id} 의 id 와 같은 값이다", example = "12")
			Long id,

			@Schema(description = "상품명", example = "아크릴 스탠드")
			String title,

			@Schema(description = "대표 이미지 주소. 등록된 이미지가 없으면 null")
			String thumbnailUrl,

			@Schema(description = "판매 유형. GROUP=공동구매(2차 공구 배지), SOLO=단독 판매")
			SaleType saleType,

			@Schema(description = "구매 버튼 활성 여부를 정하는 상태. SELLING 일 때만 살 수 있다")
			PublicStatus status,

			@Schema(description = "카드에 찍히는 대표가 — 가장 싼 옵션의 1차금+2차금 합이다. "
					+ "배송비는 들어 있지 않다", example = "32000")
			int price,

			@Schema(description = "마감까지 남은 일수(D-5 의 5). 마감일이 없으면 null, 이미 지났으면 0",
					example = "5")
			Integer dDay,

			@Schema(description = "모집 68/100 의 68. 결제가 끝난 수량만 센다. "
					+ "단독 판매이거나 셀러가 진행 현황을 감췄으면 null", example = "68")
			Integer recruitedCount,

			@Schema(description = "모집 68/100 의 100(목표 수량). 감췄으면 null", example = "100")
			Integer recruitTarget,

			@Schema(description = "살 수 있는 남은 수량. 단독 판매 카드의 '재고 5개'가 이 값이다", example = "5")
			int stock
	) {
	}

	/**
	 * @param hasNext 프론트가 무한 스크롤에 쓴다. totalPages 로 계산하게 두면
	 *                마지막 페이지 판정을 각자 다르게 하다가 한 번 더 부른다
	 */
	@Schema(description = "페이지 정보")
	public record PageInfo(

			@Schema(description = "현재 페이지 번호. 0부터 시작한다", example = "0")
			int page,

			@Schema(description = "실제 적용된 페이지 크기. 요청값이 50을 넘으면 50으로 줄어든다", example = "20")
			int size,

			@Schema(description = "조건에 맞는 전체 상품 수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가. 무한 스크롤은 이 값만 보면 된다", example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static StoreSeller sellerOf(Seller seller, String profileImageUrl) {
		return new StoreSeller(
				seller.getId(),
				seller.getStoreSlug(),
				seller.displayName(),
				seller.getBio(),
				seller.getSocialUrl(),
				profileImageUrl,
				seller.getPublicContact(),
				seller.getShippingFee(),
				seller.getFreeShippingOver()
		);
	}

	public static StoreItem itemOf(SaleForm form, LocalDateTime now, String thumbnailUrl) {
		boolean showProgress = (form.getSaleType() == SaleType.GROUP) && form.isProgressPublic();

		return new StoreItem(
				form.getId(),
				form.getTitle(),
				thumbnailUrl,
				form.getSaleType(),
				PublicStatus.of(form, now),
				lowestPrice(form),
				dDay(form.getClosesAt(), now),
				showProgress ? form.getSold() : null,
				showProgress ? form.getTargetQty() : null,
				Math.max(form.remainingStock(), 0)
		);
	}

	/**
	 * 카드에 한 값만 찍히므로 가장 싼 옵션을 쓴다.
	 * 옵션이 하나도 없는 폼은 0 이다 — 판매 폼 생성이 옵션을 강제하므로 실제로는 오지 않는다.
	 */
	private static int lowestPrice(SaleForm form) {
		return form.getProducts().stream()
				.flatMap(product -> product.getOptions().stream())
				.mapToInt(StorePageResponse::optionAmount)
				.min()
				.orElse(0);
	}

	private static int optionAmount(ProductOption option) {
		return option.getDeposit1Amount() + option.getDeposit2Amount();
	}

	private static Integer dDay(LocalDateTime closesAt, LocalDateTime now) {
		if (closesAt == null) {
			return null;
		}
		long days = Duration.between(now, closesAt).toDays();
		return (days < 0) ? 0 : (int) days;
	}
}
