package store.moeum.moeum.payment.refund;

/**
 * 취소 한 건의 금액과 세금 구성.
 *
 * point3 는 이 셋을 자동 계산하지 않는다 (D-011). 우리가 계산해 보내야 하고,
 * 불변식은 {@code taxFreeAmount + vat <= amount} 다.
 */
public record RefundTax(int amount, int taxFreeAmount, int vat) {

	public RefundTax {
		if (amount < 1) {
			throw new IllegalArgumentException("취소 금액은 1원 이상이어야 한다: " + amount);
		}
		if (taxFreeAmount < 0 || vat < 0) {
			throw new IllegalArgumentException("세금은 음수일 수 없다");
		}
		if (taxFreeAmount + vat > amount) {
			throw new IllegalArgumentException(
					"면세 + 부가세가 취소 금액을 넘는다: " + taxFreeAmount + " + " + vat + " > " + amount);
		}
	}
}
