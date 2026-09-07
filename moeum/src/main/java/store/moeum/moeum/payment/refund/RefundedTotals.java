package store.moeum.moeum.payment.refund;

/**
 * 한 결제에 대해 이미 환불된 누적. 세금 안분의 기준이다.
 *
 * sum() 이 long 을 주므로 long 으로 받고 쓰는 쪽에서 int 로 좁힌다 —
 * 금액이 int 범위를 넘을 일은 없지만 JPQL 생성자 표현식의 타입을 맞춰야 한다.
 */
public record RefundedTotals(long amount, long vat, long taxFreeAmount) {

	public int amountInt() {
		return (int) amount;
	}

	public int vatInt() {
		return (int) vat;
	}

	public int taxFreeInt() {
		return (int) taxFreeAmount;
	}
}
