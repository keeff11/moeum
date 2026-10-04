package store.moeum.moeum.payment.refund;

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
 * 취소 대사 배치가 끝내 확정하지 못하는 환불을 사람에게 넘긴다 (D-069).
 *
 * 취소 대사는 결과를 모르면 새 취소를 만들지 않고 조회 · resume 만 한다 — 다시 보내면 이중 환불이다.
 * 그래서 point3 가 계속 응답하지 않으면 <b>환불은 PROCESSING 에 머문 채 아무 일도 일어나지 않는다.</b>
 * 구매자는 "환불 처리 중" 만 보고 돈은 돌아오지 않는다.
 *
 * <pre>
 *   PROCESSING 이 warnAfter(30분) 넘게 남음      → WARN
 *   criticalAfter(6시간) 넘게 남음               → CRITICAL (@channel)
 *   point3 가 401 · 403 (우리 쪽 자격증명 문제)   → CRITICAL, 30분에 한 번
 * </pre>
 *
 * 승인과 달리 마감이 없어서 경과 시간으로 본다. <b>EOB(23:30~00:30)는 세지 않는다</b> —
 * 그 시간엔 point3 가 취소를 처리하지 않고 대사 배치도 쉰다.
 *
 * 중복 방지는 {@code refund_alert (refund_id, level)} 유니크에 {@code INSERT IGNORE} 로 선점한다.
 * 보내기에 실패하면 선점을 지워 다음 회차에 다시 보낸다.
 */
@Component
public class RefundPendingAlerter {

	private static final int SCAN_LIMIT = 100;
	private static final Duration AUTH_ALERT_COOLDOWN = Duration.ofMinutes(30);
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

	private final JdbcTemplate jdbcTemplate;
	private final AlertSender alertSender;
	private final Clock clock;
	private final Duration warnAfter;
	private final Duration criticalAfter;
	private final AlertCooldown authCooldown;

	public RefundPendingAlerter(
			JdbcTemplate jdbcTemplate,
			AlertSender alertSender,
			Clock clock,
			@Value("${moeum.alert.refund-pending.warn-after:30m}") Duration warnAfter,
			@Value("${moeum.alert.refund-pending.critical-after:6h}") Duration criticalAfter) {
		this.jdbcTemplate = jdbcTemplate;
		this.alertSender = alertSender;
		this.clock = clock;
		this.warnAfter = warnAfter;
		this.criticalAfter = criticalAfter;
		this.authCooldown = new AlertCooldown(AUTH_ALERT_COOLDOWN, clock);
	}

	/**
	 * 오래 남은 미확정 환불을 훑어 알린다. 트랜잭션 없이 돈다 (CLAUDE.md 규칙 1).
	 *
	 * 쿼리는 벽시계 기준으로 넓게 집고, EOB 를 뺀 경과 시간으로 다시 거른다.
	 *
	 * @return 이번 회차에 보낸 알림 수
	 */
	public int checkStuck() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Stuck> stuck = jdbcTemplate.query("""
				SELECT r.id, r.amount, r.requested_by, r.reason, r.created_at,
				       p.phase, p.session_id, g.order_no
				  FROM refund r
				  JOIN payment p ON p.id = r.payment_id
				  JOIN order_group g ON g.id = p.order_group_id
				 WHERE r.status = 'PROCESSING'
				   AND r.created_at <= ?
				   AND NOT EXISTS (SELECT 1 FROM refund_alert a
				                    WHERE a.refund_id = r.id AND a.level = 'CRITICAL')
				 ORDER BY r.created_at
				 LIMIT ?
				""", (rs, i) -> new Stuck(
				rs.getLong("id"),
				rs.getInt("amount"),
				rs.getString("requested_by"),
				rs.getString("reason"),
				rs.getObject("created_at", LocalDateTime.class),
				rs.getString("phase"),
				rs.getString("session_id"),
				rs.getString("order_no")
		), now.minus(warnAfter), SCAN_LIMIT);

		int sent = 0;
		for (Stuck s : stuck) {
			Duration age = EobWindow.openDuration(s.requestedAt(), now);
			if (age.compareTo(warnAfter) < 0) {
				continue;
			}
			AlertLevel level = age.compareTo(criticalAfter) < 0 ? AlertLevel.WARN : AlertLevel.CRITICAL;
			if (notify(s.refundId(), level, messageOf(s, level, age))) {
				sent++;
			}
		}
		return sent;
	}

	/**
	 * point3 가 취소 조회를 401 · 403 으로 거부했다. 고치기 전까지 모든 미확정 환불이 그대로다.
	 * 400 · 404 는 건별 문제라 여기 오지 않는다 — 오래 남으면 {@link #checkStuck} 이 잡는다.
	 */
	public void point3Rejected(Long refundId, int status) {
		String key = String.valueOf(status);
		if (!authCooldown.tryAcquire(key)) {
			return;
		}
		boolean delivered = alertSender.send(AlertLevel.CRITICAL, """
				*[취소 대사] point3 가 조회를 %d 로 거부한다*
				자격증명(POINT3_API_TOKEN) · 설정 문제다. 고치기 전까지 모든 미확정 환불이 확정되지 않는다.
				처음 걸린 건: refundId=%d""".formatted(status, refundId));
		if (!delivered) {
			authCooldown.release(key);
		}
	}

	private boolean notify(Long refundId, AlertLevel level, String text) {
		int claimed = jdbcTemplate.update(
				"INSERT IGNORE INTO refund_alert (refund_id, level) VALUES (?, ?)",
				refundId, level.name());
		if (claimed == 0) {
			return false;
		}
		if (alertSender.send(level, text)) {
			return true;
		}
		jdbcTemplate.update("DELETE FROM refund_alert WHERE refund_id = ? AND level = ?",
				refundId, level.name());
		return false;
	}

	private String messageOf(Stuck s, AlertLevel level, Duration age) {
		return """
				*[취소 대사] 환불 결과 미확정 %s 경과*%s
				결제번호 %s (refundId=%d) · 환불 %,d원 · 요청 %s
				세션 %s
				요청 %s · 사유 %s
				구매자는 '환불 처리 중' 으로 보고 있다. point3 관리자 화면에서 취소 상태를 확인한다.
				새 취소를 다시 보내지 않는다 — 이중 환불이다.""".formatted(
				human(age),
				level == AlertLevel.CRITICAL ? " — 장기 미확정" : "",
				s.paymentNo(), s.refundId(), s.amount(), s.requestedBy(),
				s.sessionId(),
				TIME.format(s.requestedAt()), s.reason() == null ? "-" : s.reason());
	}

	private static String human(Duration d) {
		long minutes = d.toMinutes();
		return minutes < 60 ? minutes + "분" : (minutes / 60) + "시간 " + (minutes % 60) + "분";
	}

	/**
	 * @param requestedAt {@code refund.created_at}. 취소는 PROCESSING 으로 태어나므로 대기 시작 시각이다.
	 *                    {@code updated_at} 은 point3 환불 id 를 받아 적을 때 밀려서 쓰지 않는다
	 */
	private record Stuck(Long refundId, int amount, String requestedBy, String reason,
	                     LocalDateTime requestedAt, String phase, String sessionId, String orderNo) {

		String paymentNo() {
			String no = orderNo == null ? "(주문번호 없음)" : orderNo;
			return no + "-" + ("FIRST".equals(phase) ? "1" : "2");
		}
	}
}
