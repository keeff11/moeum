package store.moeum.moeum.saleform.dto;

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
public record StorePageResponse(StoreSeller seller, List<StoreItem> items, PageInfo page) {

	/**
	 * 공개해도 되는 셀러 정보만.
	 * <b>대표자 실명 · 연락처 · 이메일 · 사업자번호 · 정산계좌는 담지 않는다.</b>
	 */
	public record StoreSeller(
			Long id,
			String storeSlug,
			String name,
			String bio,
			String socialUrl,
			String profileImageUrl,
			int shippingFee,
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
	public record StoreItem(
			Long id,
			String title,
			String thumbnailUrl,
			SaleType saleType,
			PublicStatus status,
			int price,
			Integer dDay,
			Integer recruitedCount,
			Integer recruitTarget,
			int stock
	) {
	}

	/**
	 * @param hasNext 프론트가 무한 스크롤에 쓴다. totalPages 로 계산하게 두면
	 *                마지막 페이지 판정을 각자 다르게 하다가 한 번 더 부른다
	 */
	public record PageInfo(int page, int size, long totalElements, int totalPages, boolean hasNext) {
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
