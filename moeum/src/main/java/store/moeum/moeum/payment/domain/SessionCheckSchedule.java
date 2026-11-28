package store.moeum.moeum.payment.domain;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 결과값이 안 온 결제를 point3 에 언제 물을지 (D-091).
 *
 * <b>처음은 세션을 붙이고 30초 뒤다.</b> point3 승인은 구매자가 확정한 당일 자정(KST)까지만 된다.
 * 그래서 첫 조회를 늦출수록 자정 직전 확정 건이 기한을 넘길 수 있다. 30초 전에 정상 confirm 이 오면
 * {@code CAPTURE_PENDING} 이 되어 대상에서 빠지므로, 정상 결제에는 point3 호출이 늘지 않는다.
 *
 * <b>그 뒤 간격은 1 → 2 → 4 → 8 → 10분이다.</b> 결제창에 오래 머무는 구매자나 창을 닫고 떠난 구매자를
 * 매번 묻지 않으려고 벌린다. 10분에서 멈추는 이유는 승인 기한이 있어서다. 더 벌리면 자정 직전 건을 놓친다.
 *
 * <b>150번 물으면 그만둔다.</b> 대략 하루다. 그때까지 point3 가 확정도 만료도 알려 주지 않은 세션이면,
 * 확정됐더라도 이미 승인 기한이 지났다.
 */
public final class SessionCheckSchedule {

	public static final Duration FIRST_DELAY = Duration.ofSeconds(30);
	static final Duration MAX_INTERVAL = Duration.ofMinutes(10);
	public static final int MAX_CHECKS = 150;

	private SessionCheckSchedule() {
	}

	/** 세션을 붙인 직후의 첫 조회 시각 */
	public static LocalDateTime first(LocalDateTime now) {
		return now.plus(FIRST_DELAY);
	}

	/**
	 * 이번에 묻고 난 뒤의 다음 조회 시각.
	 *
	 * @param checksBefore 이번 조회 전까지 물어본 횟수
	 * @return 더 묻지 않으면 null
	 */
	public static LocalDateTime next(LocalDateTime now, int checksBefore) {
		if (checksBefore + 1 >= MAX_CHECKS) {
			return null;
		}
		return now.plus(interval(checksBefore));
	}

	/** 0 → 1분, 1 → 2분, 2 → 4분, 3 → 8분, 4 이상 → 10분 */
	static Duration interval(int checksBefore) {
		if (checksBefore >= 4) {
			return MAX_INTERVAL;
		}
		Duration doubled = Duration.ofMinutes(1L << checksBefore);
		return doubled.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : doubled;
	}
}
