package store.moeum.moeum.cart.dto;

import java.util.List;

/**
 * 셀러별 장바구니 하나.
 *
 * 배송비는 셀러 단위 · 묶음당 1회라 항목이 아니라 여기에 한 번만 실린다.
 */
public record CartResponse(
		Long cartId,
		Long sellerId,
		String sellerName,
		int shippingFee,
		Integer freeShippingOver,
		int deposit1Total,
		int deposit2Total,
		boolean orderable,
		List<CartItemResponse> items
) {

	/**
	 * 장바구니 항목. 담아둔 사이 마감·품절될 수 있어 상태를 함께 내려준다.
	 *
	 * ⚠️ 이 상태는 조회 시점의 참고값이다. 실제 판정은 주문 생성의 조건부 UPDATE 다.
	 */
	public record CartItemResponse(
			Long cartItemId,
			Long saleFormId,
			String saleFormTitle,
			Long productId,
			String productName,
			Long optionId,
			String optionName,
			int qty,
			int deposit1Amount,
			int deposit2Amount,
			int remainingStock,
			ItemStatus status
	) {
	}

	public enum ItemStatus {
		/** 주문 가능 */
		AVAILABLE,
		/** 남은 재고보다 담은 수량이 많다 */
		NOT_ENOUGH_STOCK,
		/** 재고 0 */
		SOLD_OUT,
		/** 마감됐거나 판매 중이 아니다 */
		CLOSED,
		/** 1인당 구매 상한을 넘었다 */
		MAX_PER_USER_EXCEEDED
	}
}
