package store.moeum.moeum.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.outbox.domain.Outbox;
import store.moeum.moeum.outbox.domain.OutboxRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 릴레이의 DB 쓰기. <b>발송은 한 줄도 들어오지 않는다</b> (CLAUDE.md 규칙 1).
 *
 * {@link OutboxRelayBatch} 가 오케스트레이션하고 여기는 짧은 트랜잭션으로 끊어 담는다.
 * 자기호출이면 프록시를 타지 않아 {@code FOR UPDATE SKIP LOCKED} 가 걸리지 않으므로 빈을 나눈다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

	/**
	 * 발송하는 동안 다른 인스턴스가 집지 못하게 잡아 두는 시간.
	 *
	 * 발송이 이보다 오래 걸리면 같은 알림이 두 번 나갈 수 있으니 발송 타임아웃보다 넉넉해야 한다.
	 * 반대로 너무 길면 프로세스가 죽었을 때 그만큼 알림이 늦는다.
	 */
	private static final Duration LEASE = Duration.ofMinutes(1);

	/** 첫 재시도 간격. 실패할 때마다 두 배로 민다 */
	private static final Duration BACKOFF_BASE = Duration.ofSeconds(10);

	/** 아무리 밀려도 이보다 더 기다리지 않는다 */
	private static final Duration BACKOFF_MAX = Duration.ofHours(1);

	private final OutboxRepository outboxRepository;
	private final Clock clock;

	/**
	 * 보낼 건을 집으면서 임대를 건다.
	 *
	 * 엔티티가 아니라 값으로 빼내는 이유는 트랜잭션이 여기서 끝나기 때문이다 —
	 * 발송은 트랜잭션 밖에서 돌아야 한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<OutboxMessage> claim(int limit) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Outbox> claimed = outboxRepository.findSendable(now, limit);

		claimed.forEach(row -> row.lease(now.plus(LEASE)));

		return claimed.stream()
				.map(row -> new OutboxMessage(row.getId(), row.getAggregateType(), row.getAggregateId(),
						row.getEventType(), row.getPayload(), row.getRetryCount(), row.getCreatedAt()))
				.toList();
	}

	/** 발송 성공. 멱등하다 — 임대가 만료돼 두 번 집힌 건이 뒤늦게 보고할 수 있다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markSent(Long outboxId) {
		outboxRepository.findById(outboxId)
				.ifPresent(row -> row.markSent(LocalDateTime.now(clock)));
	}

	/** 발송 실패. 다음 시도를 뒤로 밀고, 상한을 넘기면 DEAD 로 내린다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markFailed(Long outboxId, String error) {
		outboxRepository.findById(outboxId).ifPresent(row -> {
			row.markFailed(error, LocalDateTime.now(clock).plus(backoffOf(row.getRetryCount())));
			if (row.isDead()) {
				// 알림이 곧 결제 요청이다. 조용히 버리면 구매자는 잔금을 낼 줄 모른다
				log.error("알림 발송을 포기한다 (DEAD): outboxId={}, eventType={}, retry={}, error={}",
						row.getId(), row.getEventType(), row.getRetryCount(), row.getLastError());
			}
		});
	}

	/**
	 * 지수 백오프. {@code retryCount} 는 아직 이번 실패가 반영되기 전 값이다.
	 *
	 * 10초 → 20 → 40 → … → 1시간에서 멈춘다.
	 */
	private static Duration backoffOf(int retryCount) {
		Duration backoff = BACKOFF_BASE.multipliedBy(1L << Math.min(retryCount, 20));
		return backoff.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : backoff;
	}
}
