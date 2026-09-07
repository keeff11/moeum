package store.moeum.moeum.payment.refund;

/**
 * 부분 취소의 세금 안분 (D-011).
 *
 * <b>결제 API 와 정반대다.</b> 세션 생성 때는 vat 를 비워 point3 가 계산하게 했지만,
 * 취소는 자동 계산이 없어 우리가 넘겨야 한다.
 *
 * <b>마지막 취소는 계산하지 않고 잔액을 그대로 쓴다.</b> 매번 반올림하면 오차가 쌓여
 * 1~2원이 남고, 그러면 원 결제가 영영 {@code fullyRefunded} 가 되지 않는다.
 */
public final class RefundTaxCalculator {

	private RefundTaxCalculator() {
	}

	/**
	 * @param refundAmount   이번에 취소할 금액
	 * @param originalAmount 원 결제 총액 — 안분의 분모
	 * @param originalVat    원 결제 부가세
	 * @param originalTaxFree 원 결제 면세액
	 * @param refundedAmount 이미 취소된 누적 금액
	 * @param refundedVat    이미 취소된 누적 부가세
	 * @param refundedTaxFree 이미 취소된 누적 면세액
	 */
	public static RefundTax split(int refundAmount,
	                              int originalAmount, int originalVat, int originalTaxFree,
	                              int refundedAmount, int refundedVat, int refundedTaxFree) {

		int remainingAmount = originalAmount - refundedAmount;
		if (refundAmount > remainingAmount) {
			throw new IllegalArgumentException(
					"취소 가능 잔액을 넘는다: " + refundAmount + " > " + remainingAmount);
		}

		int remainingVat = originalVat - refundedVat;
		int remainingTaxFree = originalTaxFree - refundedTaxFree;

		// 잔액 전부를 취소한다 — 반올림 오차가 남지 않게 잔액을 그대로 넣는다
		if (refundAmount == remainingAmount) {
			return new RefundTax(refundAmount, remainingTaxFree, remainingVat);
		}

		int vat = proportional(refundAmount, originalVat, originalAmount);
		int taxFree = proportional(refundAmount, originalTaxFree, originalAmount);

		// 반올림 때문에 잔액을 넘을 수 있다. 넘으면 잔액으로 자른다
		vat = Math.min(vat, remainingVat);
		taxFree = Math.min(taxFree, remainingTaxFree);

		// 불변식이 깨지면 부가세부터 줄인다 — 면세액은 상품 성격이라 임의로 못 줄인다
		if (taxFree + vat > refundAmount) {
			vat = Math.max(0, refundAmount - taxFree);
		}

		return new RefundTax(refundAmount, taxFree, vat);
	}

	/** 전액 취소. 원 결제 구성을 그대로 쓴다 */
	public static RefundTax full(int originalAmount, int originalVat, int originalTaxFree) {
		return new RefundTax(originalAmount, originalTaxFree, originalVat);
	}

	/** {@code round(amount × part / total)}. long 으로 올려 곱셈 오버플로를 피한다 */
	private static int proportional(int amount, int part, int total) {
		if (total <= 0 || part <= 0) {
			return 0;
		}
		return (int) Math.round((double) amount * part / total);
	}
}
