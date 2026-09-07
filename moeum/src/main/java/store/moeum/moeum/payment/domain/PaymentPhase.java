package store.moeum.moeum.payment.domain;

/**
 * 결제 차수. 결제 엔진은 하나고 이 값만 다르다 (payment-flow 0절).
 *
 * 1차금은 재고 홀드를 확정하는 절차가 붙고, 2차금은 홀드가 없다.
 */
public enum PaymentPhase {

	/** 주문 시 결제. 재고 홀드가 걸려 있다 */
	FIRST,

	/** 입고 후 잔금 + 배송비. 홀드 개념이 없다 */
	SECOND
}
