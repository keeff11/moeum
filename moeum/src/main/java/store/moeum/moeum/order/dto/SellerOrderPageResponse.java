package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.SellerOrderCounts;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.payment.domain.Payment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 주문 목록 (와이어프레임 G6) — 탭 배지 + 카드 + 페이지.
 *
 * <b>한 줄이 묶음(order_group) 하나다</b> (D-033). 결제도 2차금도 배송비도 배송지도
 * 일괄 청구도 전부 묶음 단위라, 폼 단위로 쪼개면 한 번 결제한 장바구니 주문이 여러 줄이 되어
 * 탭 건수와 청구 대상이 어긋난다. 폼이 여럿이면 카드는 "외 N건" 으로 접고 상세에서 편다.
 */
@Schema(description = "셀러 주문 목록")
public record SellerOrderPageResponse(

		@Schema(description = "상태 탭 배지 숫자")
		TabCounts counts,

		@Schema(description = "주문 카드 목록. 최신순이다")
		List<SellerOrderItem> items,

		@Schema(description = "페이지 정보")
		PageInfo page
) {

	/**
	 * 탭 옆 숫자. 탭을 바꿔도 이 값은 그대로다 — 탭 조건만 빼고 검색어·판매별 필터는 걸린 값이다.
	 */
	@Schema(description = "상태 탭별 건수")
	public record TabCounts(

			@Schema(description = "전체. 결제 전 세션과 만료 건은 세지 않는다", example = "8")
			long all,

			@Schema(description = "결제 대기 — 결제창까지 갔는데 확정되지 않은 건", example = "0")
			long paymentWaiting,

			@Schema(description = "2차금 미납 — 전 폼이 입고돼 지금 청구할 수 있는 건만 센다", example = "16")
			long secondUnpaid,

			@Schema(description = "배송 준비 중 — 2차금까지 받고 아직 안 보낸 건", example = "1")
			long preparing,

			@Schema(description = "발송 완료. 송장 등록 기능이 아직 없어 당분간 항상 0이다", example = "0")
			long shipped
	) {
	}

	/** 카드 한 장 */
	@Schema(description = "주문 카드")
	public record SellerOrderItem(

			@Schema(description = "주문번호. 셀러와 구매자가 같은 값을 부른다", example = "ORD-260830-41")
			String orderNo,

			@Schema(description = "대표 판매 폼 제목. 폼이 여럿이면 '외 N건' 이 붙는다",
					example = "아크릴 스탠드 — 2차 공구 외 1건")
			String title,

			@Schema(description = "수령인 이름. 카카오 닉네임이 아니라 배송지에 적힌 이름이다",
					example = "김서연")
			String buyerName,

			@Schema(description = "주문 총액. 1차금 + 2차금 + 배송비다", example = "32000")
			int amount,

			@Schema(description = "결제 진행 문구", example = "1차금 완료 · 2차금 미납")
			String paymentSummary,

			@Schema(description = "카드에 찍히는 상태 배지")
			SellerOrderStatus status,

			@Schema(description = "상태 배지의 한글 표기", example = "2차금 미납")
			String statusLabel,

			@Schema(description = "주문이 만들어진 시각", example = "2026-08-30T14:12:03")
			LocalDateTime orderedAt
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

			@Schema(description = "조건에 맞는 전체 주문 수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가. 무한 스크롤은 이 값만 보면 된다", example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static TabCounts countsOf(SellerOrderCounts counts) {
		return new TabCounts(counts.total(), counts.paymentWaiting(),
				counts.secondUnpaid(), counts.preparing(), counts.shipped());
	}

	/**
	 * @param shipping 없을 수 있다 — 이 기능 이전에 결제된 주문에는 스냅샷이 없다.
	 *                 그때는 카카오 닉네임으로 대신한다
	 */
	public static SellerOrderItem itemOf(OrderGroup group, Shipping shipping,
	                                     Payment first, Payment second) {
		SellerOrderStatus status = SellerOrderStatus.of(group);

		return new SellerOrderItem(
				group.getOrderNo(),
				titleOf(group),
				shipping != null ? shipping.getRecipientName() : group.getBuyer().getNickname(),
				group.firstPaymentAmount() + group.secondPaymentAmount(),
				PaymentSummary.of(first, second, group),
				status,
				status.label(),
				group.getCreatedAt());
	}

	/**
	 * 카드에는 제목이 한 줄만 들어간다. 취소된 폼은 세지 않는다 —
	 * 셀러가 보는 건수는 아직 살아 있는 주문의 수다.
	 */
	private static String titleOf(OrderGroup group) {
		List<Order> alive = group.activeOrders();

		if (alive.isEmpty()) {
			// 전부 취소된 묶음. 그래도 무엇이었는지는 보여야 한다
			return group.getOrders().isEmpty() ? "" : group.getOrders().get(0).getSaleForm().getTitle();
		}
		String head = alive.get(0).getSaleForm().getTitle();
		return alive.size() == 1 ? head : head + " 외 " + (alive.size() - 1) + "건";
	}
}
