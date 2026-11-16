package store.moeum.moeum.order;

import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;

import java.util.List;
import java.util.Map;

/**
 * 재고 확보의 교체 지점 (D-008).
 *
 * 지금 구현은 {@link StockLedger} 하나 — 판매 폼과 옵션에 조건부 UPDATE 를 한 방씩 보낸다.
 * 주문 생성({@link OrderCreator})은 이 인터페이스만 보므로, 한 상품에 몰리는 양이
 * DB 행 하나가 감당할 수 있는 양을 넘으면 구현만 바꿔 끼운다.
 *
 * <pre>
 *   조건부 UPDATE (지금) → 마감 폼 캐시 차단 → Redis 카운터 → 대기열
 * </pre>
 *
 * <b>대기열</b>은 이 인터페이스를 감싸는 구현으로 충분하다 — 차례가 온 요청만 안쪽 구현을 부른다.
 * <b>Redis 카운터</b>로 바꿀 때는 확보만 옮기면 안 된다. 해제 · 확정 · 되돌림도 같은 카운터를
 * 움직여야 하므로 {@link StockLedger} 의 나머지 세 동작을 함께 옮긴다.
 *
 * 구현은 부르는 쪽의 트랜잭션 안에서 돈다. 확보에 실패하면 예외를 던져
 * 같은 트랜잭션에서 앞서 확보한 것까지 함께 롤백되게 한다 (D-009).
 */
public interface StockGuard {

	/**
	 * 판매 폼 하나의 재고를 확보한다.
	 *
	 * @param form        확보할 판매 폼
	 * @param options     이 폼에서 주문하는 옵션들
	 * @param qtyByOption 옵션 id → 수량. 이 폼의 옵션만 들어 있어야 한다
	 * @throws store.moeum.moeum.order.exception.OutOfStockException 품절 · 마감 · 일시중지
	 */
	void acquire(SaleForm form, List<ProductOption> options, Map<Long, Integer> qtyByOption);
}
