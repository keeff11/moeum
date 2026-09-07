package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 결제 세션 단위의 전체 취소 상태.
 *
 * ⚠️ <b>{@code REFUNDABLE} 로 취소 가능 여부를 판단하지 말 것.</b>
 * 이 값의 정의는 "취소 가능" 이 아니라 <b>"처리 중이거나 완료된 취소가 없는 상태"</b> 다.
 * 부분 취소가 한 번이라도 완료되면 {@code PARTIALLY_REFUNDED} 로 바뀌므로,
 * 이 값만 보면 추가 취소를 막게 된다. 판단은 {@code canCreateRefund} · {@code refundableAmount} 로 한다.
 */
public enum RefundSessionStatus {

	/** 처리 중이거나 완료된 취소가 없는 일반 상태 */
	REFUNDABLE,
	/** 취소가 모두 완료되지 않았다 — resume 대상이 있다 */
	PROCESSING,
	PARTIALLY_REFUNDED,
	FULLY_REFUNDED,
	UNKNOWN;

	@JsonCreator
	public static RefundSessionStatus from(String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "refundable" -> REFUNDABLE;
			case "processing" -> PROCESSING;
			case "partiallyrefunded" -> PARTIALLY_REFUNDED;
			case "fullyrefunded" -> FULLY_REFUNDED;
			default -> UNKNOWN;
		};
	}
}
