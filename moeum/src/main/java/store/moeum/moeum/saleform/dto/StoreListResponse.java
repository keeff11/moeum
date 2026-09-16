package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.seller.domain.Seller;

import java.util.List;

/**
 * 셀러 리스트 — 구매자가 상점을 훑는 화면.
 *
 * <b>카드 한 장이 셀러 하나다.</b> 판매 상품은 싣지 않는다 — 셀러마다 폼을 몇 개씩
 * 끌어오면 목록 한 장에 조회가 그만큼 늘고, 화면이 보여주는 것은 프로필뿐이다.
 * 상품은 카드를 눌러 들어간 셀러 페이지({@link StorePageResponse})가 준다.
 *
 * <b>승인된 셀러만 담는다.</b> 심사가 끝나지 않은 셀러는 셀러 페이지가 404 라
 * (B0 와 같은 판단), 목록에 실으면 눌렀을 때 없는 상점으로 떨어진다.
 */
@Schema(description = "셀러 리스트 — 승인된 셀러의 공개 프로필 목록")
public record StoreListResponse(

		@Schema(description = "셀러 카드 목록")
		List<StoreListItem> items,

		@Schema(description = "페이지 정보. 무한 스크롤에 쓴다")
		PageInfo page
) {

	/**
	 * 카드 한 장.
	 *
	 * <b>공개해도 되는 값만 담는다.</b> 대표자 실명 · 심사용 연락처 · 이메일 ·
	 * 사업자번호 · 정산계좌는 물론이고, 셀러 페이지가 주는 문의 연락처와 배송비도
	 * 여기서는 뺐다 — 화면이 쓰지 않는 값을 목록 한 장에 20건씩 흘릴 이유가 없다.
	 */
	@Schema(description = "셀러 카드 한 장")
	public record StoreListItem(

			@Schema(description = "셀러 id", example = "1")
			Long id,

			@Schema(description = "판매공간 주소. 카드를 누르면 이 값으로 셀러 페이지(B0)로 간다",
					example = "moeum-store")
			String storeSlug,

			@Schema(description = "카드에 찍히는 상점 이름. 상점 이름을 등록하지 않은 셀러는 "
					+ "storeSlug 가 대신 온다 — 대표자 실명으로 대체하지 않는다",
					example = "모음 상점")
			String name,

			@Schema(description = "한 줄 소개. 등록하지 않았으면 null — 프론트가 자리를 비운다",
					example = "굿즈 선주문 전문")
			String bio,

			@Schema(description = "프로필 이미지 주소. 등록하지 않았으면 null — 프론트가 기본 이미지를 넣는다")
			String profileImageUrl
	) {
	}

	@Schema(description = "페이지 정보")
	public record PageInfo(

			@Schema(description = "현재 페이지 번호. 0부터 시작한다", example = "0")
			int page,

			@Schema(description = "실제 적용된 페이지 크기. 요청값이 50을 넘으면 50으로 줄어든다",
					example = "20")
			int size,

			@Schema(description = "조건에 맞는 전체 셀러 수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가. 무한 스크롤은 이 값만 보면 된다", example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	/**
	 * @param profileImageUrl 저장된 키를 ImageStorage 가 조립한 주소. 없으면 null
	 */
	public static StoreListItem itemOf(Seller seller, String profileImageUrl) {
		return new StoreListItem(
				seller.getId(),
				seller.getStoreSlug(),
				// 상점 이름이 비면 storeSlug 가 나온다. 검색도 같은 값을 본다
				seller.displayName(),
				seller.getBio(),
				profileImageUrl);
	}
}
