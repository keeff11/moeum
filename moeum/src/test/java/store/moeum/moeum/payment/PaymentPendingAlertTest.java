package store.moeum.moeum.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.alert.AlertSender;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 미확정 결제 알림 (D-068).
 *
 * 지키는 것은 둘이다 — <b>마감 전에 사람이 알게 한다</b>, <b>같은 건으로 두 번 울리지 않는다</b>.
 * 매분 울리는 알림은 아무도 안 본다. 그러면 정말 중요한 한 번도 묻힌다.
 *
 * 시각이 곧 분기 조건이라 시계를 고정한다. 실제 시계로 돌리면 밤 10시 이후에는 WARN 이 CRITICAL 이 된다.
 */
class PaymentPendingAlertTest extends IntegrationTest {

	/** 2026-10-03 20:00 KST. 승인 마감(10-04 00:00)까지 4시간 */
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 20, 0);
	private static final String SESSION_ID = "pymt_sess-alert-0001";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderService orderService;

	@Autowired
	private OrderFixture fixture;

	private final RecordingSender sender = new RecordingSender();
	private Long orderGroupId;

	@BeforeEach
	void setUp() {
		fixture.clean();
		fixture.buyerWithAddress("kakao-alert", "김서연");
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		orderService.place(new SessionUser("kakao-alert", "구매자"),
				new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 1))));
		orderGroupId = jdbcTemplate.queryForObject("SELECT id FROM order_group", Long.class);
		jdbcTemplate.update("UPDATE order_group SET order_no = 'ORD-261003-1' WHERE id = ?", orderGroupId);
	}

	@Test
	@DisplayName("15분이_안_된_미확정_건은_알리지_않는다")
	void 유예_안쪽() {
		pending(NOW.minusMinutes(14));

		assertThat(alerterAt(NOW).checkStuck()).isZero();
		assertThat(sender.sent).isEmpty();
	}

	@Test
	@DisplayName("15분이_지나면_WARN_을_한_번만_보낸다")
	void WARN_한_번() {
		pending(NOW.minusMinutes(20));
		PaymentPendingAlerter alerter = alerterAt(NOW);

		assertThat(alerter.checkStuck()).isEqualTo(1);
		// 배치는 1분마다 돈다. 기록이 없으면 같은 건으로 매분 울린다
		assertThat(alerter.checkStuck()).isZero();
		assertThat(alerterAt(NOW.plusMinutes(30)).checkStuck()).isZero();

		assertThat(sender.sent).hasSize(1);
		Sent warn = sender.sent.get(0);
		assertThat(warn.level()).isEqualTo(AlertLevel.WARN);
		assertThat(warn.text()).contains("ORD-261003-1-1", "99,000원", SESSION_ID, "10-04 00:00");
	}

	@Test
	@DisplayName("마감_2시간_전이면_WARN_을_보냈어도_CRITICAL_로_한_번_더_알린다")
	void CRITICAL_승격() {
		pending(NOW.minusMinutes(20));
		alerterAt(NOW).checkStuck();

		PaymentPendingAlerter late = alerterAt(LocalDateTime.of(2026, 10, 3, 22, 1));
		assertThat(late.checkStuck()).isEqualTo(1);
		assertThat(late.checkStuck()).isZero();

		assertThat(sender.sent).extracting(Sent::level)
				.containsExactly(AlertLevel.WARN, AlertLevel.CRITICAL);
		assertThat(sender.sent.get(1).text()).contains("마감 임박", "1시간 59분 남음");
	}

	@Test
	@DisplayName("처음_발견했을_때_이미_마감_임박이면_CRITICAL_만_보낸다")
	void 바로_CRITICAL() {
		pending(LocalDateTime.of(2026, 10, 3, 23, 0));

		alerterAt(LocalDateTime.of(2026, 10, 3, 23, 30)).checkStuck();

		assertThat(sender.sent).extracting(Sent::level).containsExactly(AlertLevel.CRITICAL);
	}

	@Test
	@DisplayName("마감이_지난_건은_마감_지남으로_알린다")
	void 마감_지남() {
		pending(NOW.minusDays(1));

		alerterAt(NOW).checkStuck();

		assertThat(sender.sent).singleElement()
				.satisfies(s -> {
					assertThat(s.level()).isEqualTo(AlertLevel.CRITICAL);
					assertThat(s.text()).contains("마감 지남");
				});
	}

	@Test
	@DisplayName("전송에_실패하면_다음_회차에_다시_보낸다")
	void 실패_후_재전송() {
		pending(NOW.minusMinutes(20));
		sender.succeed = false;

		assertThat(alerterAt(NOW).checkStuck()).isZero();
		// 선점을 남겨 두면 Slack 이 잠깐 끊긴 사이의 알림이 영영 안 나간다
		assertThat(alertRows()).isZero();

		sender.succeed = true;
		assertThat(alerterAt(NOW.plusMinutes(1)).checkStuck()).isEqualTo(1);
		assertThat(alertRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("재결제로_세션이_바뀌면_새_건으로_다시_알린다")
	void 재결제() {
		pending(NOW.minusMinutes(20));
		alerterAt(NOW).checkStuck();

		// D-023: 같은 payment 행을 재사용하고 세션만 갈아끼운다
		jdbcTemplate.update("UPDATE payment SET session_id = 'pymt_sess-alert-0002', updated_at = ?",
				NOW.plusMinutes(10));
		assertThat(alerterAt(NOW.plusMinutes(30)).checkStuck()).isEqualTo(1);

		assertThat(sender.sent).hasSize(2);
		assertThat(sender.sent.get(1).text()).contains("pymt_sess-alert-0002");
	}

	@Test
	@DisplayName("확정된_결제는_알리지_않는다")
	void 확정된_건() {
		pending(NOW.minusMinutes(20));
		jdbcTemplate.update("UPDATE payment SET status = 'CAPTURED'");

		assertThat(alerterAt(NOW).checkStuck()).isZero();
	}

	@Test
	@DisplayName("point3_401_은_즉시_CRITICAL_이고_30분에_한_번만_울린다")
	void 자격증명_거부() {
		MutableClock clock = new MutableClock(NOW);
		PaymentPendingAlerter alerter = new PaymentPendingAlerter(
				jdbcTemplate, sender, clock, Duration.ofMinutes(15), Duration.ofHours(2));

		alerter.point3Rejected(1L, 401);
		alerter.point3Rejected(2L, 401);
		clock.now = NOW.plusMinutes(29);
		alerter.point3Rejected(3L, 401);
		assertThat(sender.sent).hasSize(1);
		assertThat(sender.sent.get(0).level()).isEqualTo(AlertLevel.CRITICAL);

		// 상태가 다르면 다른 원인이다
		alerter.point3Rejected(4L, 403);
		clock.now = NOW.plusMinutes(31);
		alerter.point3Rejected(5L, 401);
		assertThat(sender.sent).hasSize(3);
	}

	// ---------------------------------------------------------------- 도우미

	private void pending(LocalDateTime since) {
		jdbcTemplate.update("""
				INSERT INTO payment (order_group_id, phase, session_id, amount, status, updated_at)
				VALUES (?, 'FIRST', ?, 99000, 'CAPTURE_PENDING', ?)""",
				orderGroupId, SESSION_ID, since);
	}

	private PaymentPendingAlerter alerterAt(LocalDateTime now) {
		return new PaymentPendingAlerter(jdbcTemplate, sender, new MutableClock(now),
				Duration.ofMinutes(15), Duration.ofHours(2));
	}

	private int alertRows() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_alert", Integer.class);
	}

	private record Sent(AlertLevel level, String text) {
	}

	private static class RecordingSender implements AlertSender {
		final List<Sent> sent = new ArrayList<>();
		boolean succeed = true;

		@Override
		public boolean send(AlertLevel level, String text) {
			if (succeed) {
				sent.add(new Sent(level, text));
			}
			return succeed;
		}
	}

	private static class MutableClock extends Clock {
		LocalDateTime now;

		MutableClock(LocalDateTime now) {
			this.now = now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return KST;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public java.time.Instant instant() {
			return now.atZone(KST).toInstant();
		}
	}
}
