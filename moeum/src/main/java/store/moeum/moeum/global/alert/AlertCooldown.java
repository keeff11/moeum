package store.moeum.moeum.global.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 같은 원인의 알림을 일정 시간에 한 번만 통과시킨다 (D-068).
 *
 * 401 · 403 처럼 건마다가 아니라 시스템 단위로 터지는 장애에 쓴다 — 매분 울리면 채널이 묻힌다.
 *
 * <b>인스턴스 메모리에 둔다.</b> 인스턴스마다 한 번씩 울리는 것과 재시작 직후 한 번 더 울리는 것은
 * 받아들인다. 알림 한 번 더보다 테이블 하나 더가 비싸다.
 */
public class AlertCooldown {

	private final Duration window;
	private final Clock clock;
	private final Map<String, Instant> lastPassed = new ConcurrentHashMap<>();

	public AlertCooldown(Duration window, Clock clock) {
		this.window = window;
		this.clock = clock;
	}

	/** @return 이번에 보내도 되면 true. 그 시각부터 window 동안 같은 키는 막힌다 */
	public boolean tryAcquire(String key) {
		Instant now = clock.instant();
		boolean[] acquired = {false};
		lastPassed.compute(key, (k, last) -> {
			if (last == null || !now.isBefore(last.plus(window))) {
				acquired[0] = true;
				return now;
			}
			return last;
		});
		return acquired[0];
	}

	/** 보내기에 실패했다. 다음 호출이 바로 다시 보낼 수 있게 푼다 */
	public void release(String key) {
		lastPassed.remove(key);
	}
}
