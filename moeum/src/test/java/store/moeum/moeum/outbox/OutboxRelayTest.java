package store.moeum.moeum.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.outbox.domain.Outbox;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.support.IntegrationTest;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 릴레이 (roadmap 7단계, D-012).
 *
 * <b>확인하려는 것은 유실과 중복이다.</b> 알림이 곧 결제 요청이라 유실되면 구매자가
 * 잔금을 낼 줄 모르고, 중복되면 같은 청구가 두 번 간다.
 */
@Import({OutboxRelayTest.FixedClockConfig.class, OutboxRelayTest.FakeSenderConfig.class})
class OutboxRelayTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 12, 0);

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}
	}

	/** 실패를 마음대로 만들 수 있어야 백오프와 DEAD 를 확인할 수 있다 */
	@TestConfiguration
	static class FakeSenderConfig {
		@Bean
		@Primary
		FakeSender fakeSender() {
			return new FakeSender();
		}
	}

	static class FakeSender implements NotificationSender {
		final AtomicInteger calls = new AtomicInteger();
		final List<Long> delivered = new java.util.ArrayList<>();
		volatile boolean fail = false;
		volatile Long failOnly = null;

		@Override
		public void send(OutboxMessage message) {
			calls.incrementAndGet();
			if (fail || (failOnly != null && failOnly.equals(message.aggregateId()))) {
				throw new IllegalStateException("알림톡 게이트웨이 응답 없음");
			}
			delivered.add(message.aggregateId());
		}
	}

	@Autowired
	private OutboxRelayBatch relay;

	@Autowired
	private OutboxRecorder recorder;

	@Autowired
	private FakeSender sender;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("DELETE FROM outbox");
		sender.calls.set(0);
		sender.delivered.clear();
		sender.fail = false;
		sender.failOnly = null;
	}

	// ---------------------------------------------------------------- 발송

	@Test
	@DisplayName("적재된_알림을_보내고_SENT_로_넘긴다")
	void 발송() {
		record(OutboxEventType.ORDER_PAID, 11L);

		assertThat(relay.relayOnce()).isEqualTo(1);

		assertThat(sender.delivered).containsExactly(11L);
		assertThat(statuses()).containsExactly("SENT");
		assertThat(sentAt()).isNotNull();
	}

	@Test
	@DisplayName("오래된_것부터_보낸다")
	void 순서() {
		record(OutboxEventType.ORDER_PAID, 1L);
		record(OutboxEventType.SECOND_PAYMENT_DUE, 2L);
		record(OutboxEventType.REFUND_COMPLETED, 3L);

		relay.relayOnce();

		// 2차금 청구 알림은 늦을수록 셀러의 미수가 길어진다
		assertThat(sender.delivered).containsExactly(1L, 2L, 3L);
	}

	@Test
	@DisplayName("보낸_건은_다시_보내지_않는다")
	void 중복_방지() {
		record(OutboxEventType.ORDER_PAID, 11L);

		assertThat(relay.relayOnce()).isEqualTo(1);
		assertThat(relay.relayOnce()).isZero();

		// 같은 청구가 두 번 가면 구매자가 두 번 결제한다
		assertThat(sender.calls.get()).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 실패 · 재시도

	@Test
	@DisplayName("발송이_실패하면_PENDING_으로_남고_다음_시도를_뒤로_민다")
	void 실패_백오프() {
		record(OutboxEventType.SECOND_PAYMENT_DUE, 11L);
		sender.fail = true;

		assertThat(relay.relayOnce()).isZero();

		assertThat(statuses()).containsExactly("PENDING");
		assertThat(retryCounts()).containsExactly(1);
		// 10초 뒤에 다시 시도한다. 지금 당장 또 집으면 1초마다 계속 두드린다
		assertThat(nextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
	}

	@Test
	@DisplayName("백오프_시각이_안_됐으면_집지_않는다")
	void 백오프_대기() {
		record(OutboxEventType.ORDER_PAID, 11L);
		sender.fail = true;
		relay.relayOnce();
		sender.fail = false;

		// 시계가 12:00 에 멈춰 있으니 12:00:10 은 아직 오지 않았다
		assertThat(relay.relayOnce()).isZero();
		assertThat(sender.calls.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("실패가_쌓일수록_간격이_벌어진다")
	void 지수_백오프() {
		record(OutboxEventType.ORDER_PAID, 11L);
		sender.fail = true;

		// 10초 · 20초 · 40초 — 매번 백오프를 지나간 것으로 만들어 다시 집게 한다
		assertThat(attemptAgain()).isEqualTo(NOW.plusSeconds(10));
		assertThat(attemptAgain()).isEqualTo(NOW.plusSeconds(20));
		assertThat(attemptAgain()).isEqualTo(NOW.plusSeconds(40));
		assertThat(retryCounts()).containsExactly(3);
	}

	@Test
	@DisplayName("상한을_넘기면_DEAD_로_내리고_더_보내지_않는다")
	void 상한() {
		record(OutboxEventType.SECOND_PAYMENT_DUE, 11L);
		sender.fail = true;

		for (int i = 0; i < Outbox.MAX_RETRY; i++) {
			openBackoff();
			relay.relayOnce();
		}

		assertThat(statuses()).containsExactly("DEAD");
		assertThat(retryCounts()).containsExactly(Outbox.MAX_RETRY);

		// 되살아나면 안 된다. 여기서부터는 사람이 본다
		sender.fail = false;
		openBackoff();
		assertThat(relay.relayOnce()).isZero();
	}

	@Test
	@DisplayName("실패_사유를_남긴다")
	void 실패_사유() {
		record(OutboxEventType.ORDER_PAID, 11L);
		sender.fail = true;

		relay.relayOnce();

		assertThat(lastError()).contains("알림톡 게이트웨이 응답 없음");
	}

	@Test
	@DisplayName("한_건이_실패해도_나머지는_보낸다")
	void 한_건_실패() {
		record(OutboxEventType.ORDER_PAID, 1L);
		record(OutboxEventType.ORDER_PAID, 2L);
		sender.failOnly = 1L;

		assertThat(relay.relayOnce()).isEqualTo(1);

		// 앞에서 멈추면 뒤의 구매자들은 잔금을 낼 줄 모른 채 기다린다
		assertThat(sender.delivered).containsExactly(2L);
		assertThat(statuses()).containsExactly("PENDING", "SENT");
	}

	// ---------------------------------------------------------------- 임대

	@Test
	@DisplayName("집어가는_동안_다른_인스턴스가_같은_건을_집지_못한다")
	void 임대() {
		record(OutboxEventType.SECOND_PAYMENT_DUE, 11L);

		// 릴레이가 발송하는 중이라고 치고, 집어만 둔다
		List<OutboxMessage> claimed = writer.claim(50);

		assertThat(claimed).hasSize(1);
		// 같은 행을 또 집으면 알림톡이 두 번 나간다
		assertThat(writer.claim(50)).isEmpty();
		assertThat(nextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
	}

	// ---------------------------------------------------------------- 적재

	@Test
	@DisplayName("payload_직렬화가_실패해도_예외를_던지지_않는다")
	void 직렬화_실패() {
		// 결제 확정 트랜잭션 안에서 도는 코드다. 여기서 터지면 결제가 통째로 롤백된다
		recorder.record(OutboxAggregate.ORDER_GROUP, 11L, OutboxEventType.ORDER_PAID,
				Map.of("bad", new Object()));

		assertThat(payloads()).containsExactly("{}");
	}

	// ---------------------------------------------------------------- 도우미

	@Autowired
	private OutboxWriter writer;

	private void record(OutboxEventType type, long aggregateId) {
		recorder.record(OutboxAggregate.ORDER_GROUP, aggregateId, type,
				Map.of("orderToken", "tok-" + aggregateId, "amount", 1000));
	}

	/** 백오프를 지나간 것으로 만들고 한 번 더 시도한다. 다음 시도 시각을 돌려준다 */
	private LocalDateTime attemptAgain() {
		openBackoff();
		relay.relayOnce();
		return nextAttemptAt();
	}

	/** 시계가 고정돼 있어 시간이 흐르지 않는다. 행을 과거로 당겨 같은 효과를 낸다 */
	private void openBackoff() {
		jdbcTemplate.update("UPDATE outbox SET next_attempt_at = ? WHERE status = 'PENDING'",
				java.sql.Timestamp.valueOf(NOW.minusMinutes(5)));
	}

	private List<String> statuses() {
		return jdbcTemplate.queryForList("SELECT status FROM outbox ORDER BY id", String.class);
	}

	private List<Integer> retryCounts() {
		return jdbcTemplate.queryForList("SELECT retry_count FROM outbox ORDER BY id", Integer.class);
	}

	private List<String> payloads() {
		return jdbcTemplate.queryForList("SELECT payload FROM outbox ORDER BY id", String.class);
	}

	private LocalDateTime nextAttemptAt() {
		return jdbcTemplate.queryForObject(
				"SELECT next_attempt_at FROM outbox ORDER BY id LIMIT 1", LocalDateTime.class);
	}

	private LocalDateTime sentAt() {
		return jdbcTemplate.queryForObject(
				"SELECT sent_at FROM outbox ORDER BY id LIMIT 1", LocalDateTime.class);
	}

	private String lastError() {
		return jdbcTemplate.queryForObject(
				"SELECT last_error FROM outbox ORDER BY id LIMIT 1", String.class);
	}
}
