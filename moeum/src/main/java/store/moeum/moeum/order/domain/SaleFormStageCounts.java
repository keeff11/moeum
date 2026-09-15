package store.moeum.moeum.order.domain;

/**
 * 판매 폼 하나의 진행 단계별 주문 수 (셀러 판매 상세의 단계 타임라인 · D-058).
 *
 * <b>단계마다 COUNT 를 날리지 않는다.</b> 같은 테이블을 일곱 번 훑을 이유가 없어서 한 번의
 * 집계로 받는다. {@code count(case when ... then 1 end)} 을 쓰는 이유는 {@code sum} 이
 * 한 건도 없을 때 0 이 아니라 null 을 주기 때문이다 — count 는 null 을 세지 않아 그대로 0 이다.
 * {@link SellerOrderCounts} 와 같은 방식이다.
 *
 * <b>결제 전(CREATED)과 만료(EXPIRED)는 세지 않는다.</b> 그 주문은 진행 단계에 올라온 적이
 * 없고, 세면 타임라인 첫 칸이 결제되지 않은 홀드까지 품은 숫자가 된다.
 *
 * <b>묶음이 아니라 주문 수다.</b> 진행 단계는 판매 폼별로 도는 {@code orders.status} 에만
 * 있다 — 묶음({@code order_group.status})은 결제 단계라 다른 축이다 (D-049).
 */
public record SaleFormStageCounts(long paid, long recruiting, long closed, long producing,
                                  long arrived, long shipped, long canceled) {

	/** 살아 있는 주문 수. 취소는 단계 어디에도 서 있지 않아 뺀다 */
	public long live() {
		return paid + recruiting + closed + producing + arrived + shipped;
	}

	/** 지금 입고 처리가 넘길 주문 수. {@code findArrivableBySaleForm} 과 같은 집합이다 */
	public long arrivable() {
		return paid + recruiting + closed + producing;
	}
}
