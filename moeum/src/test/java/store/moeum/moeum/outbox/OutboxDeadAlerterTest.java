package store.moeum.moeum.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.support.MutableClock;
import store.moeum.moeum.support.RecordingAlertSender;
import store.moeum.moeum.support.RecordingAlertSender.Sent;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEAD 알림 묶기 (D-069).
 *
 * 알림 채널이 죽으면 수백 건이 한꺼번에 DEAD 가 된다. 건마다 울리면 Slack 이 묻혀 아무도 안 본다.
 */
class OutboxDeadAlerterTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 14, 0);

	private final RecordingAlertSender sender = new RecordingAlertSender();
	private final MutableClock clock = new MutableClock(NOW);
	private final OutboxDeadAlerter alerter = new OutboxDeadAlerter(sender, clock);

	@Test
	@DisplayName("첫_DEAD_는_상세와_함께_바로_CRITICAL_로_보낸다")
	void 첫_건() {
		alerter.dead(message(1L, OutboxEventType.SECOND_PAYMENT_DUE), "IllegalStateException: 게이트웨이 응답 없음");

		assertThat(sender.sent).singleElement().satisfies(s -> {
			assertThat(s.level()).isEqualTo(AlertLevel.CRITICAL);
			assertThat(s.text()).contains("SECOND_PAYMENT_DUE", "outboxId=1", "게이트웨이 응답 없음");
		});
	}

	@Test
	@DisplayName("10분_안의_DEAD_는_세기만_하고_지나면_요약_한_번을_보낸다")
	void 묶기() {
		alerter.dead(message(1L, OutboxEventType.SECOND_PAYMENT_DUE), "e");
		alerter.dead(message(2L, OutboxEventType.SECOND_PAYMENT_DUE), "e");
		alerter.dead(message(3L, OutboxEventType.SECOND_PAYMENT_DUE), "e");

		clock.now = NOW.plusMinutes(9);
		alerter.flush();
		assertThat(sender.sent).hasSize(1);

		clock.now = NOW.plusMinutes(10);
		alerter.flush();
		alerter.flush();
		assertThat(sender.sent).hasSize(2);
		assertThat(sender.sent.get(1).text()).contains("DEAD 2건 더");
	}

	@Test
	@DisplayName("쌓인_게_없으면_요약을_보내지_않는다")
	void 요약_없음() {
		alerter.dead(message(1L, OutboxEventType.ORDER_PAID), "e");

		clock.now = NOW.plusHours(1);
		alerter.flush();

		assertThat(sender.sent).hasSize(1);
	}

	@Test
	@DisplayName("이벤트_종류가_다르면_따로_알린다")
	void 종류별() {
		alerter.dead(message(1L, OutboxEventType.ORDER_PAID), "e");
		alerter.dead(message(2L, OutboxEventType.SECOND_PAYMENT_DUE), "e");

		assertThat(sender.sent).hasSize(2);
	}

	@Test
	@DisplayName("쿨다운이_지난_뒤의_DEAD_는_다시_상세로_보낸다")
	void 쿨다운_후() {
		alerter.dead(message(1L, OutboxEventType.ORDER_PAID), "e");
		clock.now = NOW.plusMinutes(11);
		alerter.dead(message(2L, OutboxEventType.ORDER_PAID), "e");

		assertThat(sender.sent).extracting(Sent::text)
				.satisfiesExactly(t -> assertThat(t).contains("outboxId=1"),
						t -> assertThat(t).contains("outboxId=2"));
	}

	@Test
	@DisplayName("긴_오류_메시지는_잘라서_보낸다")
	void 오류_자르기() {
		alerter.dead(message(1L, OutboxEventType.ORDER_PAID), "x".repeat(1000));

		assertThat(sender.sent.get(0).text()).contains("x".repeat(300) + "…")
				.doesNotContain("x".repeat(301));
	}

	private static OutboxMessage message(Long id, OutboxEventType eventType) {
		return new OutboxMessage(id, OutboxAggregate.ORDER_GROUP, 10L + id, eventType, "{}", 7, NOW.minusHours(2));
	}
}
