package store.moeum.moeum.payment.refund;

/**
 * 셀러 화면의 [결제 취소] 버튼을 켤지 말지 (G10 · D-059).
 *
 * <b>판정을 두 벌 적지 않으려고 만든 값이다.</b> 버튼 조건을 화면 쪽에 따로 적으면
 * "켜져 있는데 누르면 튕기는" 버튼이 생긴다 — 취소가 실제로 세우는 계획을 그대로 세워 보고,
 * 통과하면 켜고 막히면 그 이유를 그대로 문구로 준다.
 *
 * @param refundableAmount 지금 취소하면 돌아갈 금액. 막혀 있으면 0 이다.
 *                         <b>배송비는 2차금에서 받기로 한 묶음이면 2차금을 결제한 뒤에야 들어온다</b>
 *                         (D-046) — 받은 적 없는 배송비를 돌려줄 금액에 세면 안 된다
 */
public record SellerCancelView(boolean cancelable, String blockedReason, int refundableAmount) {

	static SellerCancelView allowed(RefundPlan plan) {
		return new SellerCancelView(true, null, plan.firstAmount() + plan.secondAmount());
	}

	static SellerCancelView blocked(String reason) {
		return new SellerCancelView(false, reason, 0);
	}
}
