package store.moeum.moeum.payment.refund;

/**
 * 주문 취소 한 번을 point3 취소 몇 건으로 번역한 결과.
 *
 * 1차금과 2차금이 별도 세션이라 <b>한 번의 "취소" 가 결제 취소 두 건</b>이 된다
 * (payment-flow "1차금 / 2차금 취소 조합"). 어느 결제에서 얼마를 빼는지를 여기서 확정하고,
 * 실행은 {@link RefundService} 가 건별로 한다.
 *
 * @param orderId     폼 하나만 취소하면 그 주문 id, 묶음 전체면 null
 * @param fullGroup   묶음이 통째로 취소되는가. <b>배송비 환불 여부가 여기에 달렸다</b>
 */
public record RefundPlan(Long groupId, Long orderId, boolean fullGroup,
                         Long firstPaymentId, int firstAmount,
                         Long secondPaymentId, int secondAmount) {

	public boolean hasFirst() {
		return firstPaymentId != null && firstAmount > 0;
	}

	public boolean hasSecond() {
		return secondPaymentId != null && secondAmount > 0;
	}
}
