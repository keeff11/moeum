package store.moeum.moeum.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.alert.AlertSender;
import store.moeum.moeum.outbox.domain.Outbox;
import store.moeum.moeum.outbox.domain.OutboxEventType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 발송을 포기한(DEAD) 알림을 사람에게 넘긴다 (D-069).
 *
 * 알림이 곧 결제 요청이다. 잔금 요청 알림이 DEAD 면 구매자는 잔금을 낼 줄 모른 채 마감을 넘긴다.
 * DEAD 는 재시도가 끝난 상태라 <b>사람이 보지 않으면 영영 안 나간다.</b>
 *
 * <pre>
 *   eventType 별 첫 DEAD           → CRITICAL 즉시 (건 상세)
 *   그 뒤 10분 안의 DEAD           → 세기만 한다
 *   10분 지나고 쌓인 게 있으면     → "N건 더" 요약 한 번 ({@link #flush})
 * </pre>
 *
 * 알림 채널 장애면 수백 건이 한꺼번에 DEAD 가 된다. 건마다 울리면 Slack 이 묻힌다.
 *
 * 기록은 메모리에만 둔다. 재기동하면 첫 건이 한 번 더 울릴 뿐이고, DEAD 행은 DB 에 남아 있다.
 * 보내기에 실패해도 보낸 것으로 친다 — Slack 이 죽었을 때 1초마다 다시 두드리지 않게, 10분 뒤 요약으로 다시 알린다.
 */
@Slf4j
@Component
public class OutboxDeadAlerter {

	static final Duration COOLDOWN = Duration.ofMinutes(10);
	private static final int ERROR_MAX = 300;

	private final AlertSender alertSender;
	private final Clock clock;
	private final Map<OutboxEventType, State> states = new HashMap<>();

	public OutboxDeadAlerter(AlertSender alertSender, Clock clock) {
		this.alertSender = alertSender;
		this.clock = clock;
	}

	/** 방금 DEAD 가 된 건. 트랜잭션 밖에서 부른다 */
	public void dead(OutboxMessage message, String error) {
		String text;
		synchronized (this) {
			Instant now = clock.instant();
			State state = states.get(message.eventType());
			if (state != null && now.isBefore(state.lastSentAt.plus(COOLDOWN))) {
				state.suppressed++;
				return;
			}
			states.put(message.eventType(), new State(now));
			text = """
					*[알림 발송 포기] %s DEAD*
					outboxId=%d · %s#%s · 재시도 %d회 소진
					마지막 오류: %s
					구매자가 이 알림을 받지 못했다. 원인을 고친 뒤 outbox 를 PENDING 으로 되돌리거나 직접 연락한다.""".formatted(
					message.eventType(), message.id(), message.aggregateType(), message.aggregateId(),
					Outbox.MAX_RETRY, truncate(error));
		}
		alertSender.send(AlertLevel.CRITICAL, text);
	}

	/** 쿨다운이 지난 eventType 의 쌓인 DEAD 를 요약해 보낸다 */
	public void flush() {
		Map<OutboxEventType, Integer> due = new HashMap<>();
		synchronized (this) {
			Instant now = clock.instant();
			states.forEach((eventType, state) -> {
				if (state.suppressed > 0 && !now.isBefore(state.lastSentAt.plus(COOLDOWN))) {
					due.put(eventType, state.suppressed);
					state.suppressed = 0;
					state.lastSentAt = now;
				}
			});
		}
		due.forEach((eventType, count) -> alertSender.send(AlertLevel.CRITICAL, """
				*[알림 발송 포기] %s DEAD %d건 더*
				직전 알림 이후 %d분 동안 쌓였다. outbox 에서 status='DEAD' 를 확인한다.""".formatted(
				eventType, count, COOLDOWN.toMinutes())));
	}

	private static String truncate(String error) {
		if (error == null) {
			return "-";
		}
		return error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX) + "…";
	}

	private static final class State {
		private Instant lastSentAt;
		private int suppressed;

		private State(Instant lastSentAt) {
			this.lastSentAt = lastSentAt;
		}
	}
}
