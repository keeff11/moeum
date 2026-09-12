package store.moeum.moeum.order.domain;

/**
 * 셀러 주문 목록의 상태 탭 5종 (와이어프레임 G6).
 *
 * 묶음 상태({@link OrderGroupStatus})를 셀러가 할 일 기준으로 접은 것이다.
 * 셀러가 알고 싶은 것은 "지금 뭘 해야 하는가" 지 내부 상태 이름이 아니다.
 */
public enum SellerOrderTab {

	/** 전체. 결제 전 세션(CREATED)과 만료 건은 어느 탭에도 없다 */
	ALL,

	/** 결제 대기 — 결제창까지 갔는데 아직 확정되지 않았다 */
	PAYMENT_WAITING,

	/**
	 * 2차금 미납 — <b>청구할 수 있는 것만</b>이다.
	 *
	 * 1차금만 받은 묶음 중 <b>모든 폼이 입고된</b> 건이다. 입고 전 주문까지 넣으면
	 * 아직 청구할 수 없는 건이 청구 대상 숫자에 섞여 일괄 청구(S10)가 틀어진다.
	 * 판단 기준은 {@link OrderGroup#isSecondPaymentDue()} 와 같다.
	 */
	SECOND_UNPAID,

	/** 배송 준비 중 — 2차금까지 받았고 아직 안 보냈다 */
	PREPARING,

	/** 발송 완료 — 송장이 등록된 묶음이다 (D-047) */
	SHIPPED
}
