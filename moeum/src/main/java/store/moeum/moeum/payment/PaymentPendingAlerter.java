package store.moeum.moeum.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.alert.AlertCooldown;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.alert.AlertSender;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 대사 배치가 끝내 확정하지 못하는 결제를 사람에게 넘긴다 (D-068).
 *
 * 대사 배치는 "모르면 기다린다" 가 원칙이다 (D-005). 그 원칙 때문에 point3 가 계속
 * 응답하지 않거나 세션이 processing 에 머물면 <b>배치는 매분 돌아도 아무 일도 하지 않는다.</b>
 * 승인 마감(커밋 다음 날 00:00 KST)이 지나면 복구할 수 없으니 그 전에 사람이 알아야 한다.
 *
 * <pre>
 *   CAPTURE_PENDING 이 warnAfter(15분) 넘게 남음        → WARN
 *   승인 마감까지 criticalBefore(2시간) 안쪽 · 마감 지남  → CRITICAL (@channel)
 *   point3 가 401 · 403 (우리 쪽 자격증명 문제)          → CRITICAL, 30분에 한 번
 * </pre>
 *
 * <b>같은 건으로 두 번 울리지 않는다.</b> {@code payment_alert (session_id, level)} 유니크에
 * {@code INSERT IGNORE} 로 선점한 쪽만 보낸다. 보내기에 실패하면 선점을 지워 다음 회차에 다시 보낸다 —
 * 중복 한 번보다 유실이 비싸다.
 */
@Slf4j
@Component
public class PaymentPendingAlerter {

	/** 한 회차에 보는 양. 이보다 많이 쌓였다면 개별 건이 아니라 장애다 */
	private static final int SCAN_LIMIT = 100;

	/** 401 · 403 은 결제마다가 아니라 시스템 단위 장애다. 매분 울리면 채널이 묻힌다 */
	private static final Duration AUTH_ALERT_COOLDOWN = Duration.ofMinutes(30);

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

	private final JdbcTemplate jdbcTemplate;
	private final AlertSender alertSender;
	private final Clock clock;
	private final Duration warnAfter;
	private final Duration criticalBefore;

	private final AlertCooldown authCooldown;

	public PaymentPendingAlerter(
			JdbcTemplate jdbcTemplate,
			AlertSender alertSender,
			Clock clock,
			@Value("${moeum.alert.payment-pending.warn-after:15m}") Duration warnAfter,
			@Value("${moeum.alert.payment-pending.critical-before-deadline:2h}") Duration criticalBefore) {
		this.jdbcTemplate = jdbcTemplate;
		this.alertSender = alertSender;
		this.clock = clock;
		this.warnAfter = warnAfter;
		this.criticalBefore = criticalBefore;
		this.authCooldown = new AlertCooldown(AUTH_ALERT_COOLDOWN, clock);
	}

	/**
	 * 오래 남은 미확정 건을 훑어 알린다.
	 *
	 * <b>트랜잭션 없이 돈다.</b> Slack 호출을 트랜잭션 안에 두지 않는다 (CLAUDE.md 규칙 1).
	 *
	 * @return 이번 회차에 보낸 알림 수
	 */
	public int checkStuck() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Stuck> stuck = jdbcTemplate.query("""
				SELECT p.id, p.session_id, p.amount, p.phase, p.updated_at, g.order_no
				  FROM payment p
				  JOIN order_group g ON g.id = p.order_group_id
				 WHERE p.status = 'CAPTURE_PENDING'
				   AND p.session_id IS NOT NULL
				   AND p.updated_at <= ?
				   AND NOT EXISTS (SELECT 1 FROM payment_alert a
				                    WHERE a.session_id = p.session_id AND a.level = 'CRITICAL')
				 ORDER BY p.updated_at
				 LIMIT ?
				""", (rs, i) -> new Stuck(
				rs.getLong("id"),
				rs.getString("session_id"),
				rs.getInt("amount"),
				rs.getString("phase"),
				rs.getObject("updated_at", LocalDateTime.class),
				rs.getString("order_no")
		), now.minus(warnAfter), SCAN_LIMIT);

		int sent = 0;
		for (Stuck s : stuck) {
			AlertLevel level = levelOf(s, now);
			if (notify(s.paymentId(), s.sessionId(), level, messageOf(s, level, now))) {
				sent++;
			}
		}
		return sent;
	}

	/**
	 * point3 가 대사 조회를 401 · 403 으로 거부했다. 우리 쪽 토큰 · 설정 문제라
	 * <b>이대로면 모든 미확정 건이 마감을 넘긴다.</b> 15분을 기다리지 않고 바로 알린다.
	 *
	 * 쿨다운은 인스턴스 메모리에 둔다. 인스턴스마다 한 번씩 울리는 것은 받아들인다.
	 */
	public void point3Rejected(Long paymentId, int status) {
		String key = String.valueOf(status);
		if (!authCooldown.tryAcquire(key)) {
			return;
		}

		boolean delivered = alertSender.send(AlertLevel.CRITICAL, """
				*[결제 대사] point3 가 조회를 %d 로 거부한다*
				자격증명(POINT3_API_TOKEN) · 설정 문제다. 고치기 전까지 모든 미확정 결제가 확정되지 않는다.
				처음 걸린 건: paymentId=%d""".formatted(status, paymentId));
		if (!delivered) {
			authCooldown.release(key);
		}
	}

	private AlertLevel levelOf(Stuck s, LocalDateTime now) {
		return now.isBefore(s.deadline().minus(criticalBefore)) ? AlertLevel.WARN : AlertLevel.CRITICAL;
	}

	/** @return 이번 호출로 보냈으면 true. 이미 보낸 단계거나 전송에 실패했으면 false */
	private boolean notify(Long paymentId, String sessionId, AlertLevel level, String text) {
		int claimed = jdbcTemplate.update(
				"INSERT IGNORE INTO payment_alert (payment_id, session_id, level) VALUES (?, ?, ?)",
				paymentId, sessionId, level.name());
		if (claimed == 0) {
			return false;
		}

		if (alertSender.send(level, text)) {
			return true;
		}
		jdbcTemplate.update("DELETE FROM payment_alert WHERE session_id = ? AND level = ?",
				sessionId, level.name());
		return false;
	}

	private String messageOf(Stuck s, AlertLevel level, LocalDateTime now) {
		Duration left = Duration.between(now, s.deadline());
		String deadline = left.isNegative()
				? "*마감 지남* — 승인할 수 없다. 구매자 안내 · 수동 정리가 필요하다"
				: TIME.format(s.deadline()) + " (" + human(left) + " 남음)";

		return """
				*[결제 대사] 승인 결과 미확정 %s 경과*%s
				결제번호 %s (paymentId=%d) · %,d원
				세션 %s
				대기 시작 %s · 승인 마감 %s
				대사 배치는 계속 조회 중이다. point3 관리자 화면에서 세션 상태를 확인한다.""".formatted(
				human(Duration.between(s.pendingSince(), now)),
				level == AlertLevel.CRITICAL ? " — 마감 임박" : "",
				s.paymentNo(), s.paymentId(), s.amount(),
				s.sessionId(),
				TIME.format(s.pendingSince()), deadline);
	}

	private static String human(Duration d) {
		long minutes = Math.abs(d.toMinutes());
		return minutes < 60 ? minutes + "분" : (minutes / 60) + "시간 " + (minutes % 60) + "분";
	}

	/**
	 * @param pendingSince {@code payment.updated_at}. CAPTURE_PENDING 으로 바뀐 뒤로는
	 *                     아무도 이 행을 고치지 않으므로 대기 시작 시각이다 — 대사 배치도 같은 값을 본다
	 */
	private record Stuck(Long paymentId, String sessionId, int amount, String phase,
	                     LocalDateTime pendingSince, String orderNo) {

		/**
		 * 승인 마감. 커밋된 날의 다음 날 00:00 KST (point3-api 4절).
		 *
		 * 커밋 시각 대신 PENDING 으로 바뀐 시각을 쓴다. 둘은 보통 몇 초 차이지만
		 * 자정을 사이에 두면 실제 마감이 하루 이르다 — 그 경우 이 알림은 이미 늦다.
		 */
		LocalDateTime deadline() {
			return pendingSince.toLocalDate().plusDays(1).atStartOfDay();
		}

		String paymentNo() {
			String no = orderNo == null ? "(주문번호 없음)" : orderNo;
			return no + "-" + ("FIRST".equals(phase) ? "1" : "2");
		}
	}
}
