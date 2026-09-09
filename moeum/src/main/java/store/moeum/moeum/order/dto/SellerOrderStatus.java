package store.moeum.moeum.order.dto;

import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupStatus;

/**
 * 카드에 찍히는 상태 배지 (와이어프레임 G6).
 *
 * 탭({@link store.moeum.moeum.order.domain.SellerOrderTab})보다 값이 많다. 탭은 셀러가
 * 골라 보는 다섯 칸이지만, 배지는 <b>전체 탭에 섞여 나오는 모든 줄</b>에 하나씩 붙어야 한다 —
 * 취소된 주문과 결제가 깨진 주문도 목록에는 남는다.
 *
 * 내부 상태 이름을 그대로 내보내지 않는 이유는 셀러가 읽을 값이기 때문이다.
 * {@code SECOND_PENDING} 은 "2차금 결제를 시작했다" 는 뜻인데, 셀러 입장에서는
 * 아직 안 들어온 돈이라 {@code IN_PROGRESS} 와 구분할 이유가 없다.
 */
public enum SellerOrderStatus {

	PAYMENT_WAITING("결제 대기"),

	/** 1차금은 받았는데 아직 입고 전이다. 청구할 수 없다 */
	IN_PROGRESS("진행 중"),

	SECOND_UNPAID("2차금 미납"),

	PREPARING("배송 준비 중"),

	SHIPPED("발송 완료"),

	CANCELED("취소됨"),

	FAILED("결제 실패");

	private final String label;

	SellerOrderStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * <b>{@code isSecondPaymentDue()} 가 갈림길이다.</b> 같은 PAID 라도 전 폼이 입고됐으면
	 * 청구 대상(2차금 미납)이고, 하나라도 남았으면 아직 진행 중이다.
	 */
	public static SellerOrderStatus of(OrderGroup group) {
		OrderGroupStatus status = group.getStatus();

		return switch (status) {
			case PAY_PENDING -> PAYMENT_WAITING;
			case PAID, SECOND_PENDING -> group.isSecondPaymentDue() ? SECOND_UNPAID : IN_PROGRESS;
			case SECOND_PAID -> PREPARING;
			case SHIPPED -> SHIPPED;
			case CANCELED -> CANCELED;
			case FAILED -> FAILED;

			// CREATED · EXPIRED 는 목록 쿼리가 이미 걸러 낸다. 여기 오면 쿼리가 바뀐 것이다
			default -> throw new IllegalStateException("주문 목록에 나올 수 없는 상태다: " + status);
		};
	}
}
