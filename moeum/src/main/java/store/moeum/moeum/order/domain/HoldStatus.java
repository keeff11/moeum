package store.moeum.moeum.order.domain;

/** stock_hold.status */
public enum HoldStatus {
	/** 선점 중 */
	HELD,
	/** 결제 확정으로 sold 에 넘어감 */
	COMMITTED,
	/** 이탈·만료로 재고에 반환됨 */
	RELEASED
}
