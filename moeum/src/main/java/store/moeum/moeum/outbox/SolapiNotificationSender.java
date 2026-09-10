package store.moeum.moeum.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import store.moeum.moeum.outbox.infra.SolapiClient;
import store.moeum.moeum.outbox.infra.SolapiSendRequest;

import java.util.Optional;

/**
 * 카카오 알림톡 발송기 (SOLAPI). {@code moeum.notify.provider=solapi} 일 때 뜬다.
 *
 * <b>승인된 템플릿이 있는 이벤트만 나간다.</b> 나머지는 예전처럼 로그만 남기고 SENT 로
 * 넘긴다 — 미승인 템플릿으로 보내면 4xx 로 떨어져 8회 재시도 끝에 DEAD 로 쌓이고,
 * 그건 "아직 승인이 안 났다" 를 장애처럼 보이게 만든다.
 *
 * 템플릿 id 를 {@code moeum.notify.solapi.templates.<이벤트>} 에 채우는 순간
 * 그 이벤트가 실제로 나가기 시작한다. 코드는 손대지 않는다.
 *
 * <b>실패는 예외로 올린다.</b> 삼키면 릴레이가 성공으로 보고 SENT 로 넘겨 다시는
 * 보내지 않는다 — 2차금 청구가 그렇게 사라지면 구매자는 잔금을 낼 줄 모른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "moeum.notify.provider", havingValue = "solapi")
public class SolapiNotificationSender implements NotificationSender {

	private final AlimtalkMessageFactory messageFactory;
	private final SolapiClient solapiClient;

	@Override
	public void send(OutboxMessage message) {
		Optional<SolapiSendRequest.Message> alimtalk = messageFactory.create(message);

		if (alimtalk.isEmpty()) {
			// 승인된 템플릿이 아직 없다. 재시도해서 풀릴 일이 아니라 기다려야 하는 일이다
			log.info("[알림/템플릿없음] {} {}#{}",
					message.eventType(), message.aggregateType(), message.aggregateId());
			return;
		}

		solapiClient.send(alimtalk.get());

		// 수신번호는 남기지 않는다 (CLAUDE.md 규칙 10 과 같은 판단 — 개인정보를 로그에 두지 않는다)
		log.info("[알림/발송] {} {}#{}",
				message.eventType(), message.aggregateType(), message.aggregateId());
	}
}
