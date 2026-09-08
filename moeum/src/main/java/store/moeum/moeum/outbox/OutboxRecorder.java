package store.moeum.moeum.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import store.moeum.moeum.outbox.domain.Outbox;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.outbox.domain.OutboxRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 알림 이벤트를 적재한다 (D-012).
 *
 * <b>{@code @Transactional} 을 붙이지 않는다.</b> 이건 실수가 아니라 이 클래스의 전부다 —
 * 호출자의 트랜잭션에 그대로 합류해야 사실과 알림이 같이 커밋된다. 여기에
 * {@code REQUIRES_NEW} 를 붙이면 결제가 롤백돼도 알림만 남아 "결제되지 않은 주문의
 * 결제 완료 알림" 이 나간다.
 *
 * 적재만 하고 보내지 않는다. 발송은 {@link OutboxRelayBatch} 가 트랜잭션 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	/**
	 * @param payload 발송에 필요한 최소한만. <b>토큰 · PIN · 전체 payerId 는 넣지 않는다</b>
	 *                (CLAUDE.md 규칙 10) — DB 에 남고 실패하면 로그에도 실린다
	 */
	public void record(OutboxAggregate aggregateType, Long aggregateId,
	                   OutboxEventType eventType, Map<String, Object> payload) {
		outboxRepository.save(Outbox.of(aggregateType, aggregateId, eventType,
				toJson(eventType, payload), LocalDateTime.now(clock)));
	}

	/**
	 * payload 직렬화가 알림 때문에 결제를 깨뜨리게 두지 않는다.
	 *
	 * 여기서 예외를 던지면 호출자의 트랜잭션이 통째로 롤백된다 —
	 * 알림 하나 못 만든 대가로 결제 확정이 날아가는 것은 맞바꿀 만한 거래가 아니다.
	 */
	private String toJson(OutboxEventType eventType, Map<String, Object> payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException e) {
			log.error("알림 payload 직렬화 실패: eventType={}", eventType, e);
			return "{}";
		}
	}
}
