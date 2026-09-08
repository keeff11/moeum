package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(description = "찜한 상품 id 목록. 카드 정보는 담지 않는다 — 어느 카드에 하트를 채울지만 알려준다")
public record WishlistResponse(

		@Schema(description = "찜한 판매 폼 id 목록. 최근에 찜한 것이 앞에 온다. "
				+ "셀러 페이지 카드의 id 와 대조해 하트를 칠하면 된다",
				example = "[12, 7, 3]")
		List<Long> saleFormIds,

		@Schema(description = "찜한 개수. 목록을 다 받지 않고도 배지에 숫자를 띄우라고 따로 준다", example = "3")
		int count
) {

	public static WishlistResponse of(List<Long> saleFormIds) {
		return new WishlistResponse(saleFormIds, saleFormIds.size());
	}
}
