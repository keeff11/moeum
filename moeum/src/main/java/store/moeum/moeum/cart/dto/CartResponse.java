package store.moeum.moeum.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.SaleType;

import java.util.List;

/**
 * 셀러별 장바구니 하나.
 *
 * 배송비는 셀러 단위 · 묶음당 1회라 항목이 아니라 여기에 한 번만 실린다.
 */
public record CartResponse(
		@Schema(description = "장바구니 id", example = "3")
		Long cartId,

		@Schema(description = "셀러 id. 장바구니는 셀러당 하나다 — 배송비가 셀러 단위라 섞을 수 없다",
				example = "1")
		Long sellerId,

		@Schema(description = "상점 이름", example = "모음 상점")
		String sellerName,

		@Schema(description = "셀러 페이지 주소(meoum.store/{storeSlug}). "
				+ "장바구니 묶음 머리에서 셀러 페이지(B0)로 가는 링크가 이 값을 쓴다",
				example = "moeum-store")
		String storeSlug,

		@Schema(description = "셀러가 정한 기본 배송비. 무료배송 기준을 적용하기 전 값이다",
				example = "3000")
		int shippingFee,

		@Schema(description = "이 금액 이상이면 배송비 면제. 설정하지 않았으면 null", example = "50000")
		Integer freeShippingOver,

		@Schema(description = "지금 담긴 것으로 주문하면 실제로 붙는 배송비. "
				+ "무료배송 기준을 적용한 결과라 면제되면 0 이다. 화면에는 이 값을 쓴다",
				example = "0")
		int estimatedShippingFee,

		@Schema(description = "1차금 합계. 주문할 때 바로 결제하는 금액이다", example = "60000")
		int deposit1Total,

		@Schema(description = "2차금 합계. 입고 후 청구될 잔금이다. 배송비는 별도다", example = "36000")
		int deposit2Total,

		@Schema(description = "지금 주문할 수 있는가. 항목이 모두 AVAILABLE 이어야 true 다",
				example = "true")
		boolean orderable,

		@Schema(description = "담긴 항목들")
		List<CartItemResponse> items
) {

	/**
	 * 장바구니 항목. 담아둔 사이 마감·품절될 수 있어 상태를 함께 내려준다.
	 *
	 * ⚠️ 이 상태는 조회 시점의 참고값이다. 실제 판정은 주문 생성의 조건부 UPDATE 다.
	 */
	@Schema(description = "장바구니 항목 하나")
	public record CartItemResponse(

			@Schema(description = "장바구니 항목 id. 수량 변경·삭제에 쓴다", example = "17")
			Long cartItemId,

			@Schema(description = "판매 폼 id. 상품 상세로 이동할 때 쓴다", example = "12")
			Long saleFormId,

			@Schema(description = "상품명", example = "아크릴 스탠드")
			String saleFormTitle,

			@Schema(description = "판매 유형. GROUP=공동구매, SOLO=단독 판매. "
					+ "항목 배지와 안내 문구가 이 값으로 갈린다 — 공동구매는 입고 뒤 2차금이 더 붙는다")
			SaleType saleType,

			@Schema(description = "상품 id", example = "5")
			Long productId,

			@Schema(description = "상품 이름", example = "아크릴 스탠드")
			String productName,

			@Schema(description = "옵션 id", example = "31")
			Long optionId,

			@Schema(description = "옵션명", example = "블루")
			String optionName,

			@Schema(description = "담은 수량", example = "2")
			int qty,

			@Schema(description = "옵션 1차금(개당)", example = "20000")
			int deposit1Amount,

			@Schema(description = "옵션 2차금(개당)", example = "12000")
			int deposit2Amount,

			@Schema(description = "조회 시점의 남은 재고. 참고값이라 주문 시점에 달라질 수 있다",
					example = "5")
			int remainingStock,

			@Schema(description = "이 항목을 지금 주문할 수 있는지. AVAILABLE 이 아니면 주문이 막힌다")
			ItemStatus status
	) {
	}

	@Schema(description = """
			AVAILABLE=주문 가능 · NOT_ENOUGH_STOCK=담은 수량이 재고보다 많음 · SOLD_OUT=품절
			· CLOSED=마감 · MAX_PER_USER_EXCEEDED=1인당 구매 상한 초과""")
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
