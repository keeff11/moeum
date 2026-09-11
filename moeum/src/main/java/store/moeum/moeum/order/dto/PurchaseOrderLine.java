package store.moeum.moeum.order.dto;

/**
 * 발주서 한 줄 — 옵션 하나의 수량.
 *
 * <b>이름은 스냅샷이 아니라 지금 이름이다.</b> {@code order_item} 에 주문 시점 이름이
 * 남아 있지만(표시용 스냅샷), 발주서는 공장에 "무엇을 몇 개 만들라" 고 보내는 문서다.
 * 셀러가 중간에 옵션 이름을 바꿨다면 공장이 받아야 하는 것은 <b>바뀐 이름</b> 하나이고,
 * 스냅샷으로 묶으면 같은 옵션이 옛 이름 · 새 이름 두 줄로 갈라진다.
 *
 * @param productName  상품 이름
 * @param optionName   옵션 이름
 * @param orderedQty   결제까지 간 주문 수량. 취소분을 아직 빼지 않았다
 * @param canceledQty  그중 취소된 수량
 */
public record PurchaseOrderLine(String productName, String optionName,
                                long orderedQty, long canceledQty) {

	/** 실제로 발주할 수량 */
	public long netQty() {
		return orderedQty - canceledQty;
	}
}
