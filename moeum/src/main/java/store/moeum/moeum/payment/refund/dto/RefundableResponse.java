package store.moeum.moeum.payment.refund.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 취소 화면에 필요한 것 전부.
 *
 * <b>부작용이 없다</b> — point3 취소 이력 조회도 부르지 않는다. 이 응답은 우리 DB 와 시계만 본다.
 * point3 잔액 대조는 실제 취소를 누른 뒤 {@code RefundService} 가 한다.
 *
 * @param eobOpensAt            지금 EOB 로 막혀 있으면 풀리는 시각, 아니면 null
 * @param shippingFeeRefundable 남은 폼을 전부 취소해야만 배송비가 함께 환불된다
 */
public record RefundableResponse(

		@Schema(description = "이 주문을 가리키는 토큰")
		String orderToken,

		@Schema(description = "지금 취소할 수 있는 폼이 하나라도 있는가. false 면 취소 버튼을 막는다",
				example = "true")
		boolean refundable,

		@Schema(description = "취소가 막힌 이유. 사용자에게 그대로 보여도 된다. 가능하면 null")
		String blockedReason,

		@Schema(description = "PG 정산 시간대(23:30~00:30)라 지금 취소가 막혀 있으면 풀리는 시각. "
				+ "막혀 있지 않으면 null")
		LocalDateTime eobOpensAt,

		@Schema(description = "배송비", example = "3000")
		int shippingFee,

		@Schema(description = "남은 폼을 전부 취소하면 배송비도 함께 돌려받는가. "
				+ "일부만 취소하면 배송은 그대로 나가므로 배송비는 유지된다", example = "true")
		boolean shippingFeeRefundable,

		@Schema(description = "폼별 취소 가능 여부와 환불 예정액")
		List<Item> items) {

	/**
	 * 폼 하나. {@code orderId} 를 취소 요청에 그대로 넘기면 이 폼만 취소된다.
	 *
	 * @param refundAmount 이 폼만 취소할 때 돌려받는 금액. 배송비는 들어 있지 않다
	 */
	@Schema(description = "폼 하나의 취소 정보. orderId 를 취소 요청에 넘기면 이 폼만 취소된다")
	public record Item(

			@Schema(description = "주문 id. 부분 취소 요청의 orderId 로 그대로 쓴다", example = "44")
			Long orderId,

			@Schema(description = "상품명", example = "아크릴 스탠드")
			String saleFormTitle,

			@Schema(description = "주문 수량", example = "2")
			int qty,

			@Schema(description = "이 폼을 지금 취소할 수 있는가", example = "true")
			boolean refundable,

			@Schema(description = "취소가 막힌 이유. 사용자에게 그대로 보여도 된다. 가능하면 null",
					example = "발주가 시작되어 취소할 수 없습니다. 판매자에게 문의해 주세요.")
			String blockedReason,

			@Schema(description = "돌려받을 1차금", example = "40000")
			int firstAmount,

			@Schema(description = "돌려받을 2차금. 아직 2차금을 결제하지 않았으면 0", example = "24000")
			int secondAmount,

			@Schema(description = "이 폼만 취소할 때 돌려받는 총액. 배송비는 들어 있지 않다",
					example = "64000")
			int refundAmount) {
	}
}
