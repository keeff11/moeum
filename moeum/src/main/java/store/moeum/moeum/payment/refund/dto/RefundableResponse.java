package store.moeum.moeum.payment.refund.dto;

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
public record RefundableResponse(String orderToken,
                                 boolean refundable,
                                 String blockedReason,
                                 LocalDateTime eobOpensAt,
                                 int shippingFee,
                                 boolean shippingFeeRefundable,
                                 List<Item> items) {

	/**
	 * 폼 하나. {@code orderId} 를 취소 요청에 그대로 넘기면 이 폼만 취소된다.
	 *
	 * @param refundAmount 이 폼만 취소할 때 돌려받는 금액. 배송비는 들어 있지 않다
	 */
	public record Item(Long orderId,
	                   String saleFormTitle,
	                   int qty,
	                   boolean refundable,
	                   String blockedReason,
	                   int firstAmount,
	                   int secondAmount,
	                   int refundAmount) {
	}
}
