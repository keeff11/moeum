package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 취소 상태 조회 응답 ({@code PaymentRefundStatusResponse}).
 *
 * <b>취소 가능 판단은 {@link #canCreateRefund()} 와 {@link #refundableAmount()} 로 한다.</b>
 * {@code status} 로 판단하면 부분 취소 후 추가 취소를 막게 된다.
 *
 * @param refundableAmount 현재 추가로 취소할 수 있는 금액. 부분 취소의 상한
 * @param originalAmount   원 결제 총액. 세금 안분의 분모
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Point3RefundStatus(
		String paymentSessionId,
		RefundSessionStatus status,
		Integer originalAmount,
		Integer refundableAmount,
		String productName,
		Boolean canCreateRefund,
		List<Point3RefundEntry> refunds
) {

	public Point3RefundStatus {
		refunds = (refunds == null) ? List.of() : refunds;
	}

	/** 새 취소를 만들 수 있는가. 값이 없으면 만들지 않는다 — 모르면 안 하는 쪽이 안전하다 */
	public boolean canCreate() {
		return Boolean.TRUE.equals(canCreateRefund);
	}

	/** resume 으로 밀어야 할 항목이 있는가 */
	public boolean hasProcessing() {
		return status == RefundSessionStatus.PROCESSING
				|| refunds.stream().anyMatch(Point3RefundEntry::isProcessing);
	}

	public int refundable() {
		return refundableAmount == null ? 0 : refundableAmount;
	}
}
