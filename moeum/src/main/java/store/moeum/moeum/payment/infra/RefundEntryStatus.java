package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/** 개별 취소 항목의 상태 ({@code refunds[].status}) */
public enum RefundEntryStatus {

	COMPLETED,
	FAILED,
	/** 아직 끝나지 않았다. resume 으로 밀어야 한다 */
	PROCESSING,
	/** 명세에 없는 값. 완료로 넘기지 않는다 */
	UNKNOWN;

	@JsonCreator
	public static RefundEntryStatus from(String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		try {
			return valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return UNKNOWN;
		}
	}
}
