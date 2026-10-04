package store.moeum.moeum.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/** 테스트가 바늘을 옮길 수 있는 KST 시계 */
public class MutableClock extends Clock {

	public LocalDateTime now;

	public MutableClock(LocalDateTime now) {
		this.now = now;
	}

	@Override
	public ZoneId getZone() {
		return KST;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Instant instant() {
		return now.atZone(KST).toInstant();
	}
}
