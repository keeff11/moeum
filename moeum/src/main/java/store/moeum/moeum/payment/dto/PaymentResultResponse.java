package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.payment.refund.RefundRequester;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 결과. confirm 응답이자 상태 조회 응답이다.
 *
 * <b>PENDING 은 실패가 아니다.</b> 승인 결과를 아직 모르는 상태이고, 프론트는 여기서
 * "확인 중" 화면을 띄우고 상태 조회를 반복해야 한다. 실패로 안내하면 사용자가
 * 다시 결제해 이중 결제가 된다.
 *
 * <b>{@code orders} 와 {@code canceledBy} 는 상태 조회에만 실린다.</b> 주문 상세(B8)가
 * 이 응답 하나로 화면을 그리기 때문이다 — 폼별 진행 단계가 {@code orders.status} 에 있고,
 * 묶음 상태로는 그걸 표현할 수 없다. 승인 직후 응답에는 비어 있다.
 */
public record PaymentResultResponse(
		@Schema(description = "이 주문을 가리키는 토큰")
		String orderToken,

		@Schema(description = "결제 결과. PENDING 은 실패가 아니다 — 다시 결제시키면 이중 결제가 된다")
		Status status,

		@Schema(description = "PENDING 인 이유. <b>둘의 대응이 정반대다</b> — "
				+ "AWAITING_PAYMENT 는 결제를 이어서 진행해야 하고, CONFIRMING 은 기다리기만 해야 한다. "
				+ "PENDING 이 아니면 null")
		PendingReason pendingReason,

		@Schema(description = "사용자에게 그대로 보여도 되는 안내 문구")
		String message,

		@Schema(description = "주문 전체가 취소됐는가. <b>status 와 따로 본다</b> — 취소해도 결제 자체는 "
				+ "CAPTURED 로 남아 status 는 PAID 그대로다", example = "false")
		boolean canceled,

		@Schema(description = "이 차수에서 지금까지 환불된 금액. 폼 하나만 취소하면 canceled 는 false 인데 "
				+ "이 값만 올라간다", example = "0")
		int refundedAmount,

		@Schema(description = "취소를 요청한 주체. BUYER=구매자 · SELLER=판매자 · SYSTEM=목표수량 미달 "
				+ "자동 취소. <b>확정된 취소가 하나도 없으면 null 이다</b> — 일부 폼만 취소된 주문은 "
				+ "canceled 가 false 인데도 이 값은 채워진다. 취소 안내 문구가 주체에 따라 갈린다",
				example = "BUYER")
		RefundRequester canceledBy,

		@Schema(description = "판매 폼별 주문. <b>상태 조회에만 실린다</b> — 승인 응답에서는 비어 있다. "
				+ "폼마다 입고 시점이 달라 진행 단계가 여기서 따로 돈다")
		List<OrderLine> orders
) {

	@Schema(description = """
			PAID=결제 완료 · PENDING=결과 확인 중(다시 결제시키지 말고 상태 조회를 반복한다) · FAILED=실패""")
	public enum Status {
		/** 출금 완료. 이것만이 성공이다 */
		PAID,
		/** 결과 확인 중. 대사 배치가 확정한다 — 다시 결제하게 하면 안 된다 */
		PENDING,
		/** 확정 실패. 홀드가 풀렸고 다시 시도할 수 있다 */
		FAILED
	}

	/**
	 * PENDING 하나에 서로 다른 두 상황이 들어 있다.
	 *
	 * <b>대응이 정반대라 구분해서 내려 준다.</b> 합쳐 두면 프론트는 둘 중 하나를 틀린다 —
	 * 승인 대기 중에 결제를 다시 띄우면 이중 결제고, 결제창을 안 끝낸 건을 계속
	 * 폴링만 하면 영원히 PENDING 이다.
	 */
	@Schema(description = """
			AWAITING_PAYMENT=결제창을 아직 끝내지 않았다(이어서 결제해야 한다) · 			CONFIRMING=승인 결과 대기 중(기다리기만 한다. 다시 결제시키면 이중 결제)""")
	public enum PendingReason {

		/**
		 * 세션만 만들어졌고 승인 요청이 오지 않았다.
		 *
		 * <b>폴링만 해서는 영원히 안 바뀐다.</b> 사용자가 결제창을 닫았거나 새로고침한
		 * 경우가 대부분이라, 결제를 이어서 진행하게 해야 한다.
		 * 홀드가 만료되면 만료 배치가 걷어 간다.
		 */
		AWAITING_PAYMENT,

		/**
		 * 승인을 요청했고 결과를 모른다 (CAPTURE_PENDING).
		 *
		 * <b>여기서 다시 결제시키면 이중 결제다.</b> 실제로 출금됐을 수 있다 —
		 * 대사 배치가 point3 에 물어 확정할 때까지 폴링만 한다 (D-005).
		 */
		CONFIRMING
	}

	/**
	 * 판매 폼 하나에 대한 주문. 부분 취소의 단위이자 <b>진행 단계가 도는 단위</b>다.
	 *
	 * {@code OrderGroupResponse.OrderLine}(결제 전 세션 조회) 과 이름과 금액 필드가 같지만
	 * {@code status} · {@code canceledAt} 이 더 있다. 저쪽은 아직 결제 전이라 전부
	 * {@code CREATED} 이고 취소라는 개념이 없어서, 같은 레코드로 묶으면 쓰지 않는 필드가
	 * 절반인 응답이 된다.
	 */
	@Schema(description = "판매 폼 하나에 대한 주문. 진행 단계와 부분 취소의 단위다")
	public record OrderLine(

			@Schema(description = "주문 id. 폼 하나만 취소할 때 이 값을 보낸다", example = "44")
			Long orderId,

			@Schema(description = "판매 폼 id. 상품 상세로 이동할 때 쓴다", example = "12")
			Long saleFormId,

			@Schema(description = "상품명", example = "아크릴 스탠드")
			String saleFormTitle,

			@Schema(description = "판매 유형. GROUP=공동구매, SOLO=단독 판매. "
					+ "단독은 모집·제작 단계가 없어 화면 흐름이 다르다")
			SaleType saleType,

			@Schema(description = "이 폼에서 주문한 총 수량", example = "2")
			int qty,

			@Schema(description = "이 폼의 1차금 합계", example = "40000")
			int deposit1Sum,

			@Schema(description = "이 폼의 2차금 합계. 배송비는 들어 있지 않다", example = "24000")
			int deposit2Sum,

			@Schema(description = "이 폼의 진행 단계. PAID=결제 완료 · RECRUITING=모집 중 "
					+ "· CLOSED=모집 마감 · PRODUCING=제작 중 · ARRIVED=입고 · SHIPPED=발송 "
					+ "· CANCELED=취소. <b>묶음 상태가 아니라 폼별 값이다</b> — 폼마다 입고 시점이 다르다",
					example = "PRODUCING")
			OrderStatus status,

			@Schema(description = "이 폼이 취소된 시각. 취소되지 않았으면 null",
					example = "2026-09-01T10:22:41")
			LocalDateTime canceledAt,

			@Schema(description = "주문한 옵션들")
			List<ItemLine> items
	) {
	}

	@Schema(description = "주문한 옵션 한 줄. 주문 시점의 이름·금액을 그대로 남긴다 — "
			+ "셀러가 나중에 옵션을 바꿔도 이미 산 주문의 내역은 변하지 않는다")
	public record ItemLine(

			@Schema(description = "옵션 id", example = "31")
			Long optionId,

			@Schema(description = "주문 당시의 상품명", example = "아크릴 스탠드")
			String productName,

			@Schema(description = "주문 당시의 옵션명", example = "블루")
			String optionName,

			@Schema(description = "수량", example = "2")
			int qty,

			@Schema(description = "주문 당시의 1차금(개당)", example = "20000")
			int deposit1Amount,

			@Schema(description = "주문 당시의 2차금(개당)", example = "12000")
			int deposit2Amount
	) {
	}

	public static PaymentResultResponse paid(String orderToken) {
		return new PaymentResultResponse(orderToken, Status.PAID, null,
				"결제가 완료되었습니다.", false, 0, null, List.of());
	}

	/** 승인 결과 대기. 폴링만 해야 하는 쪽이다 */
	public static PaymentResultResponse pending(String orderToken) {
		return new PaymentResultResponse(orderToken, Status.PENDING, PendingReason.CONFIRMING,
				"결제 결과를 확인하고 있습니다. 잠시만 기다려 주세요.", false, 0, null, List.of());
	}

	/** 결제창을 아직 끝내지 않았다. 이어서 결제해야 하는 쪽이다 */
	public static PaymentResultResponse awaitingPayment(String orderToken) {
		return new PaymentResultResponse(orderToken, Status.PENDING, PendingReason.AWAITING_PAYMENT,
				"결제가 완료되지 않았습니다. 결제를 이어서 진행해 주세요.", false, 0, null, List.of());
	}

	public static PaymentResultResponse failed(String orderToken, String message) {
		return new PaymentResultResponse(orderToken, Status.FAILED, null, message,
				false, 0, null, List.of());
	}

	/**
	 * 상태 조회에만 얹는 것들 — 취소 사실(D-036)과 주문 내역.
	 *
	 * <b>승인 직후 응답(confirm)에는 쓰지 않는다.</b> 그 시점에는 환불이 있을 수 없고,
	 * 주문 내역은 복귀 페이지가 상태 조회로 다시 받는다.
	 *
	 * 전부 취소면 안내 문구도 바꾼다 — "결제가 완료되었습니다" 를 그대로 두면
	 * 취소한 구매자가 결제가 살아 있다고 읽는다.
	 */
	public PaymentResultResponse withOrderDetail(boolean canceled, int refundedAmount,
	                                             RefundRequester canceledBy, List<OrderLine> orders) {
		String text = canceled ? "취소가 완료된 주문입니다." : message;
		return new PaymentResultResponse(orderToken, status, pendingReason, text,
				canceled, refundedAmount, canceledBy, orders);
	}

	/**
	 * 묶음의 주문을 줄로 편다.
	 *
	 * <b>취소된 폼도 남긴다.</b> 구매자가 무엇을 취소했는지는 상세에서 확인할 수 있어야 한다 —
	 * 빼 버리면 결제 금액과 화면의 상품 목록이 맞지 않는다.
	 */
	public static List<OrderLine> linesOf(OrderGroup group) {
		return group.getOrders().stream()
				.map(PaymentResultResponse::lineOf)
				.toList();
	}

	private static OrderLine lineOf(Order order) {
		return new OrderLine(
				order.getId(),
				order.getSaleForm().getId(),
				order.getSaleForm().getTitle(),
				order.getSaleForm().getSaleType(),
				order.getQty(),
				order.getDeposit1Sum(),
				order.getDeposit2Sum(),
				order.getStatus(),
				order.getCanceledAt(),
				order.getItems().stream()
						.map(item -> new ItemLine(
								item.getOption().getId(),
								item.getProductName(),
								item.getOptionName(),
								item.getQty(),
								item.getDeposit1Amount(),
								item.getDeposit2Amount()))
						.toList());
	}
}
