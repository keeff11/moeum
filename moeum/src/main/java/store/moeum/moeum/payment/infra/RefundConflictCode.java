package store.moeum.moeum.payment.infra;

import java.util.Locale;

/**
 * 취소 409 의 {@code result.code} (point3-api 8절).
 *
 * <b>여섯 개가 두 부류다.</b> 4단계에서 4xx/5xx 를 나눈 것과 같은 논리가 409 안에서 반복된다.
 *
 * <pre>
 *   확정 거절 → 취소가 일어나지 않았다. 되돌려도 안전
 *   모름     → 조회로 확인해야 한다. 새 취소를 만들면 이중 환불
 * </pre>
 */
public enum RefundConflictCode {

	// ---- 모름. 조회 → resume 으로 수렴한다 ----

	/**
	 * 외부 처리 결과 미확정. <b>point3 판 CAPTURE_PENDING 이다.</b>
	 * point3 도 카드사 결과를 몰라 확답을 못 주는 상태고, 실패가 아니다.
	 */
	REFUND_TEMPORARY_UNAVAILABLE(false),

	/** 같은 키를 24시간 안에 재사용. 기존 결과를 돌려주지 않으므로 조회해야 한다 */
	REFUND_DUPLICATE_REQUEST(false),

	/** 처리 중인 취소가 이미 있다. 새로 만들지 말고 resume 으로 이어받는다 */
	REFUND_ACTIVE_REQUEST_EXISTS(false),

	// ---- 확정 거절. 되돌려도 안전하다 ----

	/** 취소 가능한 상태가 아니다. 요청 종료 */
	REFUND_NOT_IN_REFUNDABLE_STATE(true),

	/** 정산 처리 중. 시스템으로는 취소할 수 없어 셀러 직접 환불로 넘긴다 */
	SETTLEMENT_DEADLINE_EXCEEDED(true),

	/** 23:30~00:30 KST 차단. 00:30 이후 새 취소로 다시 시도한다 */
	EOB_WINDOW_BLOCKED(true),

	/**
	 * 명세에 없는 코드.
	 *
	 * <b>'모름' 으로 분류한다.</b> 확정 거절로 다루면 실제로 취소된 건을 실패 처리해
	 * 홀드를 풀거나 재고를 되돌리게 된다. 모르는 것은 모른다고 두는 편이 항상 안전하다.
	 */
	UNKNOWN(false);

	private final boolean confirmedRejection;

	RefundConflictCode(boolean confirmedRejection) {
		this.confirmedRejection = confirmedRejection;
	}

	/** 취소가 일어나지 않았음이 확실하다. 실패로 확정해도 된다 */
	public boolean isConfirmedRejection() {
		return confirmedRejection;
	}

	/** 결과를 모른다. 조회 → resume 으로 확인해야 한다 */
	public boolean needsStatusCheck() {
		return !confirmedRejection;
	}

	/** 잠시 뒤 다시 시도하면 되는 코드. EOB 만 해당한다 */
	public boolean isRetryableLater() {
		return this == EOB_WINDOW_BLOCKED;
	}

	public static RefundConflictCode from(String raw) {
		if (raw == null || raw.isBlank()) {
			return UNKNOWN;
		}
		try {
			return valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return UNKNOWN;
		}
	}
}
