package store.moeum.moeum.buyer.dto;

import java.util.List;

/**
 * 찜한 판매 폼 id 목록.
 *
 * 카드 정보를 담지 않는다 — 셀러 페이지는 이미 목록을 받아 놓았고,
 * 필요한 것은 어느 카드에 하트를 채울지뿐이다.
 *
 * @param count 별도로 주는 이유는 배지 때문이다. 프론트가 배열 길이를 세게 하면
 *              목록을 다 받아야만 개수를 알 수 있다
 */
public record WishlistResponse(List<Long> saleFormIds, int count) {

	public static WishlistResponse of(List<Long> saleFormIds) {
		return new WishlistResponse(saleFormIds, saleFormIds.size());
	}
}
