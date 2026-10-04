package store.moeum.moeum.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.refund.RefundPendingAlerter;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.MutableClock;
import store.moeum.moeum.support.OrderFixture;
import store.moeum.moeum.support.RecordingAlertSender;
import store.moeum.moeum.support.RecordingAlertSender.Sent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미확정 환불 알림 (D-069).
 *
 * 취소 대사는 결과를 모르면 새 취소를 만들지 않고 기다리기만 한다. point3 가 끝내 답하지 않으면
 * 환불은 PROCESSING 에 머물고 구매자 돈은 돌아오지 않는다. 그걸 사람이 알게 하는지,
 * 같은 건으로 두 번 울리지 않는지, EOB 를 기다린 시간으로 치지 않는지 본다.
 */
class RefundPendingAlertTest extends IntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 14, 0);
	private static final String SESSION_ID = "pymt_sess-refund-alert-0001";

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderService orderService;

	@Autowired
	private OrderFixture fixture;

	private final RecordingAlertSender sender = new RecordingAlertSender();
	private Long paymentId;

	@BeforeEach
	void setUp() {
		fixture.clean();
		fixture.buyerWithAddress("kakao-refund-alert", "김서연");
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		orderService.place(new SessionUser("kakao-refund-alert", "구매자"),
				new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 1))));
		Long orderGroupId = jdbcTemplate.queryForObject("SELECT id FROM order_group", Long.class);
		jdbcTemplate.update("UPDATE order_group SET order_no = 'ORD-261003-7' WHERE id = ?", orderGroupId);
		jdbcTemplate.update("""
				INSERT INTO payment (order_group_id, phase, session_id, amount, status)
				VALUES (?, 'FIRST', ?, 99000, 'CAPTURED')""", orderGroupId, SESSION_ID);
		paymentId = jdbcTemplate.queryForObject("SELECT id FROM payment", Long.class);
	}

	@Test
	@DisplayName("30분이_안_된_미확정_환불은_알리지_않는다")
	void 유예_안쪽() {
		processing(NOW.minusMinutes(29));

		assertThat(alerterAt(NOW).checkStuck()).isZero();
		assertThat(sender.sent).isEmpty();
	}

	@Test
	@DisplayName("30분이_지나면_WARN_을_한_번만_보낸다")
	void WARN_한_번() {
		processing(NOW.minusMinutes(40));
		RefundPendingAlerter alerter = alerterAt(NOW);

		assertThat(alerter.checkStuck()).isEqualTo(1);
		assertThat(alerter.checkStuck()).isZero();
		assertThat(alerterAt(NOW.plusHours(1)).checkStuck()).isZero();

		assertThat(sender.sent).singleElement().satisfies(s -> {
			assertThat(s.level()).isEqualTo(AlertLevel.WARN);
			assertThat(s.text()).contains("ORD-261003-7-1", "30,000원", "BUYER", SESSION_ID, "40분");
		});
	}

	@Test
	@DisplayName("6시간이_지나면_CRITICAL_로_한_번_더_알린다")
	void CRITICAL_승격() {
		processing(NOW.minusMinutes(40));
		alerterAt(NOW).checkStuck();

		RefundPendingAlerter late = alerterAt(NOW.minusMinutes(40).plusHours(6));
		assertThat(late.checkStuck()).isEqualTo(1);
		assertThat(late.checkStuck()).isZero();

		assertThat(sender.sent).extracting(Sent::level)
				.containsExactly(AlertLevel.WARN, AlertLevel.CRITICAL);
		assertThat(sender.sent.get(1).text()).contains("장기 미확정");
	}

	@Test
	@DisplayName("EOB_시간은_기다린_시간으로_치지_않는다")
	void EOB_제외() {
		// 23:20 에 들어왔다. 23:30~00:30 은 point3 도 대사 배치도 쉰다
		processing(LocalDateTime.of(2026, 10, 3, 23, 20));

		// 벽시계로는 80분이지만 열려 있던 시간은 20분이다
		assertThat(alerterAt(LocalDateTime.of(2026, 10, 4, 0, 40)).checkStuck()).isZero();
		assertThat(alerterAt(LocalDateTime.of(2026, 10, 4, 1, 1)).checkStuck()).isEqualTo(1);
		assertThat(sender.sent.get(0).text()).contains("41분");
	}

	@Test
	@DisplayName("확정된_환불은_알리지_않는다")
	void 확정된_건() {
		processing(NOW.minusHours(1));
		jdbcTemplate.update("UPDATE refund SET status = 'COMPLETED'");

		assertThat(alerterAt(NOW).checkStuck()).isZero();
	}

	@Test
	@DisplayName("전송에_실패하면_다음_회차에_다시_보낸다")
	void 실패_후_재전송() {
		processing(NOW.minusMinutes(40));
		sender.succeed = false;

		assertThat(alerterAt(NOW).checkStuck()).isZero();
		assertThat(alertRows()).isZero();

		sender.succeed = true;
		assertThat(alerterAt(NOW.plusMinutes(1)).checkStuck()).isEqualTo(1);
		assertThat(alertRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("point3_401_은_즉시_CRITICAL_이고_30분에_한_번만_울린다")
	void 자격증명_거부() {
		MutableClock clock = new MutableClock(NOW);
		RefundPendingAlerter alerter = new RefundPendingAlerter(
				jdbcTemplate, sender, clock, Duration.ofMinutes(30), Duration.ofHours(6));

		alerter.point3Rejected(1L, 401);
		alerter.point3Rejected(2L, 401);
		clock.now = NOW.plusMinutes(31);
		alerter.point3Rejected(3L, 401);

		assertThat(sender.sent).extracting(Sent::level)
				.containsExactly(AlertLevel.CRITICAL, AlertLevel.CRITICAL);
		assertThat(sender.sent.get(0).text()).contains("401", "refundId=1");
	}

	// ---------------------------------------------------------------- 도우미

	private void processing(LocalDateTime createdAt) {
		jdbcTemplate.update("""
				INSERT INTO refund (payment_id, amount, requested_by, reason, status, created_at)
				VALUES (?, 30000, 'BUYER', '단순 변심', 'PROCESSING', ?)""", paymentId, createdAt);
	}

	private RefundPendingAlerter alerterAt(LocalDateTime now) {
		return new RefundPendingAlerter(jdbcTemplate, sender, new MutableClock(now),
				Duration.ofMinutes(30), Duration.ofHours(6));
	}

	private int alertRows() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_alert", Integer.class);
	}
}
