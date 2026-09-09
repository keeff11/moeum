package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 상세 드로어 (와이어프레임 G6) — 결제 · 상품/옵션 · 구매자 정보 · 주문 상태 네 칸.
 *
 * <b>배송지 본문은 내려보내지 않는다.</b> 화면의 그 칸은 '배송지(잠금)' 이다 —
 * 목록을 훑는 동안 주소가 계속 응답에 실려 나갈 이유가 없다. 발송 단계에서
 * 송장을 등록할 때 따로 여는 것이 맞다 (7단계).
 */
@Schema(description = "셀러 주문 상세")
public record SellerOrderDetailResponse(

		@Schema(description = "주문번호", example = "ORD-260830-41")
		String orderNo,

		@Schema(description = "주문이 만들어진 시각", example = "2026-08-30T14:12:03")
		LocalDateTime orderedAt,

		@Schema(description = "상태 배지")
		SellerOrderStatus status,

		@Schema(description = "상태 배지의 한글 표기", example = "2차금 미납")
		String statusLabel,

		@Schema(description = "결제 — 차수별 진행")
		PaymentSection payment,

		@Schema(description = "상품 · 옵션. 주문 시점 스냅샷이라 셀러가 상품명을 바꿔도 바뀌지 않는다")
		List<ItemLine> items,

		@Schema(description = "구매자 정보")
		BuyerSection buyer,

		@Schema(description = "주문 상태 — 다음 단계로 넘어갈 수 있는가")
		NextStep nextStep
) {

	@Schema(description = "결제 진행")
	public record PaymentSection(

			@Schema(description = "한 줄 요약", example = "1차금 완료 · 2차금 미납")
			String summary,

			@Schema(description = "주문 총액. 1차금 + 2차금 + 배송비다", example = "32000")
			int total,

			@Schema(description = "배송비. 2차금에 함께 청구된다", example = "3000")
			int shippingFee,

			@Schema(description = "차수별 줄. 아직 시작하지 않은 차수도 한 줄로 나온다")
			List<PhaseLine> phases
	) {
	}

	@Schema(description = "결제 차수 한 줄")
	public record PhaseLine(

			@Schema(description = "차수. FIRST=1차금, SECOND=2차금(잔금+배송비)", example = "FIRST")
			PaymentPhase phase,

			@Schema(description = "이 차수의 진행 문구", example = "1차금 완료")
			String label,

			@Schema(description = "이 차수의 청구 금액", example = "29000")
			int amount,

			@Schema(description = "결제가 끝났는가. 확인 중은 false 지만 실패도 아니다", example = "true")
			boolean settled
	) {
	}

	@Schema(description = "상품 · 옵션 한 줄")
	public record ItemLine(

			@Schema(description = "판매 폼 제목", example = "아크릴 스탠드 — 2차 공구")
			String saleFormTitle,

			@Schema(description = "주문 시점의 상품명", example = "아크릴 스탠드")
			String productName,

			@Schema(description = "주문 시점의 옵션명", example = "옵션 A")
			String optionName,

			@Schema(description = "수량", example = "1")
			int qty,

			@Schema(description = "이 폼만 따로 취소됐는가. 묶음 전체 취소가 아닐 수 있다", example = "false")
			boolean canceled
	) {
	}

	@Schema(description = "구매자 정보")
	public record BuyerSection(

			@Schema(description = "수령인 이름. 카카오 닉네임이 아니라 배송지에 적힌 이름이다",
					example = "김서연")
			String recipientName,

			@Schema(description = "가운데를 가린 연락처. 원본은 응답에 담기지 않는다",
					example = "010-****-1234")
			String maskedPhone,

			@Schema(description = "배송지 스냅샷이 있는가. 없으면 이 기능 이전에 결제된 주문이다",
					example = "true")
			boolean hasAddress
	) {
	}

	@Schema(description = "다음 단계")
	public record NextStep(

			@Schema(description = "다음에 할 일", example = "2차금 청구")
			String action,

			@Schema(description = "지금 할 수 있는가", example = "true")
			boolean available,

			@Schema(description = "못 하면 그 이유. 할 수 있으면 null", example = "아직 입고되지 않은 상품이 있습니다.")
			String blockReason
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static SellerOrderDetailResponse of(OrderGroup group, Shipping shipping,
	                                           Payment first, Payment second) {
		SellerOrderStatus status = SellerOrderStatus.of(group);

		return new SellerOrderDetailResponse(
				group.getOrderNo(),
				group.getCreatedAt(),
				status,
				status.label(),
				paymentOf(group, first, second),
				itemsOf(group),
				buyerOf(group, shipping),
				nextStepOf(group, status));
	}

	private static PaymentSection paymentOf(OrderGroup group, Payment first, Payment second) {
		List<PhaseLine> phases = List.of(
				new PhaseLine(PaymentPhase.FIRST, PaymentSummary.firstLabelOf(first),
						group.firstPaymentAmount(), PaymentSummary.isSettled(first)),
				new PhaseLine(PaymentPhase.SECOND, PaymentSummary.secondLabelOf(second, group),
						group.secondPaymentAmount(), PaymentSummary.isSettled(second)));

		return new PaymentSection(
				PaymentSummary.of(first, second, group),
				group.firstPaymentAmount() + group.secondPaymentAmount(),
				group.getShippingFee(),
				phases);
	}

	private static List<ItemLine> itemsOf(OrderGroup group) {
		List<ItemLine> lines = new ArrayList<>();

		for (Order order : group.getOrders()) {
			boolean canceled = order.getStatus() == OrderStatus.CANCELED;

			for (OrderItem item : order.getItems()) {
				lines.add(new ItemLine(order.getSaleForm().getTitle(), item.getProductName(),
						item.getOptionName(), item.getQty(), canceled));
			}
		}
		return lines;
	}

	private static BuyerSection buyerOf(OrderGroup group, Shipping shipping) {
		if (shipping == null) {
			// 스냅샷을 도입하기 전에 결제된 주문이다. 있는 것만 준다
			return new BuyerSection(group.getBuyer().getNickname(), "****", false);
		}
		return new BuyerSection(shipping.getRecipientName(), shipping.maskedPhone(), true);
	}

	/**
	 * 화면의 "2차금 미납 → 발송 준비 전환 불가" 한 줄이 여기서 나온다.
	 * 왜 못 넘어가는지를 같이 주지 않으면 셀러는 버튼이 왜 안 눌리는지 알 수 없다.
	 */
	private static NextStep nextStepOf(OrderGroup group, SellerOrderStatus status) {
		return switch (status) {
			case PAYMENT_WAITING -> new NextStep("1차금 결제 완료", false, "구매자가 아직 결제하지 않았습니다.");
			case IN_PROGRESS -> new NextStep("2차금 청구", false, "아직 입고되지 않은 상품이 있습니다.");
			case SECOND_UNPAID -> new NextStep("2차금 청구", true, null);
			case PREPARING -> new NextStep("발송 · 송장 등록", true, null);
			case SHIPPED -> new NextStep("없음", false, "발송까지 끝난 주문입니다.");
			case CANCELED -> new NextStep("없음", false, "취소된 주문입니다.");
			case FAILED -> new NextStep("없음", false, "결제가 완료되지 않은 주문입니다.");
		};
	}
}
