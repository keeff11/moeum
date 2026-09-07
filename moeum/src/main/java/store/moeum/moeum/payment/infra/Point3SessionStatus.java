package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * point3 세션 상태 (point3-api 3절).
 *
 * <pre>
 * created → identified → initiated → committed → processing → captured
 *                                                           → failed
 * </pre>
 *
 * <b>committed 는 "돈이 빠져나갔다" 가 아니라 "이제 돈을 빼도 된다" 이다.</b>
 * 여기서 승인 API 를 불러야 실제 출금이 일어난다. 이걸 성공으로 오해하면
 * 상품은 나가고 돈은 안 들어온다 (CLAUDE.md 규칙 6·7).
 */
public enum Point3SessionStatus {

	CREATED,
	IDENTIFIED,
	INITIATED,
	/** 구매자 확정. 아직 출금 전 — 승인 API 를 부를 차례다 */
	COMMITTED,
	/** 승인 처리 중. 결과를 모른다 — 되돌리지 말고 재조회한다 */
	PROCESSING,
	/** 출금 완료. 이것만이 성공이다 */
	CAPTURED,
	FAILED,
	/** 승인 마감(커밋 다음 날 00:00 KST)이 지났다. 세션을 처음부터 다시 시작해야 한다 */
	EXPIRED,
	/** 명세에 없던 값. 모르는 상태를 성공으로 넘기지 않으려고 둔다 */
	UNKNOWN;

	@JsonCreator
	public static Point3SessionStatus from(String raw) {
		if (raw == null) {
			return UNKNOWN;
		}
		try {
			return valueOf(raw.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return UNKNOWN;
		}
	}

	/** 결과를 아직 모르는 상태. 되돌리면 안 된다 */
	public boolean isPending() {
		return this == PROCESSING || this == COMMITTED;
	}

	/** 되돌려도 안전한 확정 실패 */
	public boolean isTerminalFailure() {
		return this == FAILED || this == EXPIRED;
	}
}
