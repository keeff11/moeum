package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 구매자 주문 목록 (와이어프레임 B13 — 나의 구매 목록).
 *
 * <b>한 줄이 묶음(order_group) 하나다.</b> 결제도 배송비도 취소도 묶음 단위라,
 * 폼 단위로 쪼개면 한 번 결제한 장바구니 주문이 여러 줄이 되고 금액 합이 맞지 않는다.
 * 셀러 목록(G6)과 같은 판단이다 (D-033).
 */
@Schema(description = "구매자 주문 목록")
public record BuyerOrderPageResponse(

		@Schema(description = "주문 카드. 최신순이다")
		List<BuyerOrderItem> items,

		@Schema(description = "페이지 정보")
		PageInfo page
) {

	/** 카드 한 장 */
	@Schema(description = "주문 카드")
	public record BuyerOrderItem(

			@Schema(description = "이 주문을 가리키는 토큰. 카드를 누르면 이 값으로 상세(B8)로 간다",
					example = "ord_01K4Z8Q2N7V9")
			String orderToken,

			@Schema(description = "주문번호. 문의할 때 셀러와 같은 값을 부른다",
					example = "ORD-260830-41")
			String orderNo,

			@Schema(description = "대표 상품 썸네일. 이미지가 없으면 null — 프론트가 자리표시자를 넣는다")
			String thumbnailUrl,

			@Schema(description = "대표 판매 폼 제목. 폼이 여럿이면 '외 N건' 이 붙는다",
					example = "아크릴 스탠드")
			String title,

			@Schema(description = "상점 이름. 카드 머리에 찍힌다 — 묶음은 셀러 하나로 제한되므로 한 값이다",
					example = "모음 상점")
			String sellerName,

			@Schema(description = "셀러 페이지 주소(meoum.store/{storeSlug}). "
					+ "카드에서 셀러 페이지(B0)로 가는 링크가 이 값을 쓴다",
					example = "moeum-store")
			String storeSlug,

			@Schema(description = "주문 총액. 1차금 + 2차금 + 배송비다", example = "32000")
			int amount,

			@Schema(description = "공동구매인지 단독 판매인지. 탭이 이 값으로 갈린다")
			SaleType saleType,

			@Schema(description = "카드에 찍히는 상태 배지")
			BuyerOrderStatus status,

			@Schema(description = "상태 배지의 한글 표기", example = "제작 중")
			String statusLabel,

			@Schema(description = """
					지금 취소할 수 있는가. <b>확정 판단이 아니다</b> — 우리 규칙(D-025)만 본 값이라 \
					실제로 누르면 PG 사정(정산 시간대 등)으로 막힐 수 있다. \
					확정은 GET /orders/{orderToken}/refundable 이 한다""",
					example = "true")
			boolean cancelable,

			@Schema(description = "카드 세 번째 줄 문구", example = "취소 가능")
			String note,

			@Schema(description = "택배사. 송장이 등록되기 전에는 null 이다 (D-047)", example = "CJ대한통운")
			String carrier,

			@Schema(description = "송장번호. 송장이 등록되기 전에는 null 이다", example = "123456789012")
			String trackingNo,

			@Schema(description = "주문한 시각", example = "2026-08-30T14:12:03")
			LocalDateTime orderedAt
	) {
	}

	@Schema(description = "페이지 정보")
	public record PageInfo(

			@Schema(description = "현재 페이지 번호. 0부터 시작한다", example = "0")
			int page,

			@Schema(description = "실제 적용된 페이지 크기. 요청값이 50을 넘으면 50으로 줄어든다",
					example = "20")
			int size,

			@Schema(description = "조건에 맞는 전체 주문 수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가. 무한 스크롤은 이 값만 보면 된다", example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	/**
	 * @param cancelable {@code RefundPolicy} 로 판정한 값. <b>서비스가 넘겨준다</b> —
	 *                   정책이 refund 패키지에 있어 DTO 가 그쪽을 알 이유가 없다
	 */
	public static BuyerOrderItem itemOf(OrderGroup group, String thumbnailUrl, boolean cancelable,
	                                    Shipping shipping) {
		BuyerOrderStatus status = BuyerOrderStatus.of(group);

		// LAZY 프록시다. 카드마다 깨우지만 default_batch_fetch_size 가 한 번에 끌어온다 —
		// 목록 쿼리에 fetch join 을 걸지 않는 이유는 저쪽(findBuyerOrders) 주석에 있다
		Seller seller = group.getSeller();

		return new BuyerOrderItem(
				group.getOrderToken(),
				group.getOrderNo(),
				thumbnailUrl,
				group.representativeTitle(),
				seller.displayName(),
				seller.getStoreSlug(),
				group.firstPaymentAmount() + group.secondPaymentAmount(),
				saleTypeOf(group),
				status,
				status.label(),
				cancelable,
				noteOf(status, cancelable),
				// 배송지 본문은 싣지 않는다. 구매자 본인 것이지만 목록 한 장에 20건이 실린다
				(shipping == null) ? null : shipping.getCarrier(),
				(shipping == null) ? null : shipping.getTrackingNo(),
				group.getCreatedAt());
	}

	/**
	 * 카드 세 번째 줄.
	 *
	 * <b>2차금 미납이 취소 문구보다 앞선다.</b> 구매자가 행동해야 하는 유일한 상태라
	 * "취소 가능" 으로 덮이면 잔금을 내야 하는 줄 모른다.
	 */
	private static String noteOf(BuyerOrderStatus status, boolean cancelable) {
		if (status == BuyerOrderStatus.SECOND_UNPAID) {
			return "2차금 미납";
		}
		if (status == BuyerOrderStatus.CANCELED || status == BuyerOrderStatus.FAILED) {
			return status.label();
		}
		return cancelable ? "취소 가능" : "취소 불가";
	}

	/**
	 * 묶음의 판매 유형. 한 묶음은 한 셀러이지만 유형까지 같으리라는 보장은 없어
	 * 첫 폼을 기준으로 한다 — 탭 필터도 EXISTS 라 같은 기준이다.
	 */
	private static SaleType saleTypeOf(OrderGroup group) {
		List<Order> orders = group.getOrders();
		return orders.isEmpty() ? null : orders.get(0).getSaleForm().getSaleType();
	}
}
