package store.moeum.moeum.outbox;

import java.time.LocalDateTime;

import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;

/**
 * 릴레이가 발송기에 넘기는 한 건.
 *
 * 엔티티를 그대로 넘기지 않는다 — 발송은 트랜잭션 밖에서 일어나고,
 * 준영속 엔티티를 들고 나가면 지연 로딩에서 터진다.
 *
 * @param retryCount 지금까지 실패한 횟수. 발송기가 재시도를 다르게 다루고 싶을 때 쓴다
 * @param createdAt  사실이 일어난 시각. 알림 본문의 "결제일시" 가 이 값이다 —
 *                   재시도로 며칠 뒤에 나가도 문구는 그때 그 시각이어야 한다
 */
public record OutboxMessage(Long id, OutboxAggregate aggregateType, Long aggregateId,
                            OutboxEventType eventType, String payload, int retryCount,
                            LocalDateTime createdAt) {
}
