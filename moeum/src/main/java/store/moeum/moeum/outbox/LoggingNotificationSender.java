package store.moeum.moeum.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 알림톡이 붙기 전까지 쓰는 임시 발송기 — 로그만 남기고 성공으로 친다.
 *
 * <b>이걸 쓰는 동안 구매자에게는 아무것도 가지 않는다.</b> outbox 행은 SENT 로 넘어가므로
 * 나중에 알림톡을 붙여도 그 사이 건들은 다시 나가지 않는다. 실제 연동 전까지
 * 2차금 청구는 셀러가 따로 알리거나 구매자가 직접 들어와야 한다.
 *
 * {@code moeum.notify.provider} 로 갈아 끼운다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "moeum.notify.provider", havingValue = "log", matchIfMissing = true)
public class LoggingNotificationSender implements NotificationSender {

	@Override
	public void send(OutboxMessage message) {
		log.info("[알림] {} {}#{} payload={}",
				message.eventType(), message.aggregateType(), message.aggregateId(), message.payload());
	}
}
