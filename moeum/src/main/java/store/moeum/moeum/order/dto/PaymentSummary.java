package store.moeum.moeum.order.dto;

import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentStatus;

/**
 * 결제 진행 문구 — {@code 1차금 완료 · 2차금 미납} / {@code 완납}.
 *
 * <b>{@code CAPTURE_PENDING} 을 실패로 쓰지 않는다.</b> 그건 승인 결과를 모르는 상태이지
 * 실패가 아니다 (D-006). 셀러 화면에 "실패" 라고 찍으면 실제로는 출금된 건을 두고
 * 셀러가 구매자에게 재결제를 요구하게 되고, 그러면 이중 결제가 된다.
 *
 * 목록 카드와 상세 드로어가 같은 문구를 써야 해서 여기 한곳에 둔다.
 */
final class PaymentSummary {

	private PaymentSummary() {
	}

	static String of(Payment first, Payment second, OrderGroup group) {
		if (isSettled(second)) {
			return "완납";
		}
		return firstLabelOf(first) + " · " + secondLabelOf(second, group);
	}

	static String firstLabelOf(Payment first) {
		if (first == null) {
			return "1차금 결제 전";
		}
		return switch (first.getStatus()) {
			case CAPTURED -> "1차금 완료";
			case CAPTURE_PENDING -> "1차금 확인 중";
			case FAILED -> "1차금 실패";
			case CREATED -> "1차금 결제 전";
		};
	}

	/**
	 * 2차금 행은 청구를 시작해야 생긴다. 행이 없다고 미납이라 부를 수는 없어서,
	 * 청구할 수 있는 시점(전 폼 입고)에 이르렀는지로 문구를 가른다.
	 */
	static String secondLabelOf(Payment second, OrderGroup group) {
		if (second == null) {
			return group.isSecondPaymentDue() ? "2차금 미납" : "2차금 청구 전";
		}
		return switch (second.getStatus()) {
			case CAPTURED -> "2차금 완료";
			case CAPTURE_PENDING -> "2차금 확인 중";
			case FAILED -> "2차금 실패";
			case CREATED -> "2차금 미납";
		};
	}

	/** 돈이 들어온 것만 true 다. 확인 중은 아직 아니지만 실패도 아니다 */
	static boolean isSettled(Payment payment) {
		return payment != null && payment.getStatus() == PaymentStatus.CAPTURED;
	}
}
