package store.moeum.moeum.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 적재된 알림을 실제로 내보낸다 (D-012, payment-flow 7절).
 *
 * <pre>
 *   결제 확정 트랜잭션 ──▶ outbox(PENDING)  ★ 같이 커밋된다
 *                              │
 *   릴레이 (1초) ──────────────┘  집으면서 임대 → 발송 → SENT
 *                                  실패하면 백오프, 8번 넘기면 DEAD
 * </pre>
 *
 * <b>{@code @Transactional} 이 없다.</b> 발송이 외부 호출이기 때문이다 (CLAUDE.md 규칙 1).
 * DB 쓰기는 {@link OutboxWriter} 의 짧은 트랜잭션으로 나간다.
 *
 * <b>한 건이 터져도 나머지는 계속한다.</b> 알림 하나가 배치를 멈추면 뒤의 구매자들은
 * 잔금을 낼 줄 모른 채 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayBatch {

	private static final int BATCH_SIZE = 50;

	private final OutboxWriter writer;
	private final NotificationSender sender;

	@Scheduled(fixedDelayString = "${moeum.batch.outbox-delay:1000}")
	public void run() {
		try {
			relayOnce();
		} catch (RuntimeException e) {
			// 배치가 죽으면 다음 주기가 오지 않는다. 한 번의 실패로 멈추지 않게 한다
			log.error("Outbox 릴레이 실패", e);
		}
	}

	/** 한 바퀴. 보낸 건수를 돌려준다 — 테스트가 직접 부른다 */
	public int relayOnce() {
		List<OutboxMessage> messages = writer.claim(BATCH_SIZE);
		int sent = 0;

		for (OutboxMessage message : messages) {
			if (deliver(message)) {
				sent++;
			}
		}
		if (sent > 0) {
			log.debug("알림 발송: {}건", sent);
		}
		return sent;
	}

	private boolean deliver(OutboxMessage message) {
		try {
			sender.send(message);
			writer.markSent(message.id());
			return true;
		} catch (RuntimeException e) {
			writer.markFailed(message.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
			log.warn("알림 발송 실패: outboxId={}, eventType={}, retry={}",
					message.id(), message.eventType(), message.retryCount());
			return false;
		}
	}
}
