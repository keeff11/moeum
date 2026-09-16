package store.moeum.moeum.dev.console;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 백엔드 개발자용 운영 콘솔의 뒷단. <b>로컬 프로파일에서만 만들어진다.</b>
 *
 * 하는 일은 읽기뿐이다. 상태를 바꾸는 API 는 여기 두지 않는다 —
 * 콘솔이 재고나 결제를 건드릴 수 있게 되는 순간 콘솔도 결제 도메인 코드가 된다.
 *
 * 액추에이터를 더 열지 않고 MXBean 에서 직접 읽는다. 운영 노출면을 늘리지 않으려는 것이다.
 */
@Slf4j
@Service
@Profile("local")
@RequiredArgsConstructor
public class DevConsoleService {

	/** 승인 결과를 이만큼 모르고 있으면 사람이 봐야 한다 (규칙 3 — 되돌리지 않고 기다린다) */
	private static final int CAPTURE_PENDING_STUCK_MINUTES = 5;
	private static final int RECENT_EVENT_LIMIT = 12;
	private static final int MAX_PEEK_ROWS = 200;
	private static final int HEAP_WARN_PERCENT = 85;
	private static final long MB = 1024L * 1024L;

	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA);

	private final JdbcTemplate jdbc;
	private final DataSource dataSource;
	private final Environment environment;
	private final Clock clock;

	// ------------------------------------------------------------------
	// 개요
	// ------------------------------------------------------------------

	public ConsoleSnapshot snapshot() {
		ConsoleSnapshot.App app = app();
		ConsoleSnapshot.Pool pool = pool();
		ConsoleSnapshot.Db db = db();
		ConsoleSnapshot.Flyway flyway = db.reachable() ? flyway() : noFlyway();
		ConsoleSnapshot.Pulse pulse = db.reachable() ? pulse() : emptyPulse();

		return new ConsoleSnapshot(
				LocalDateTime.now(clock).format(STAMP),
				app, pool, db, flyway, pulse,
				signals(app, pool, db, flyway, pulse));
	}

	private ConsoleSnapshot.App app() {
		var runtime = ManagementFactory.getRuntimeMXBean();
		var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		long max = heap.getMax() > 0 ? heap.getMax() : heap.getCommitted();

		// 로컬은 spring.profiles.default 로 뜬다 — active 는 비어 있고 default 에 local 이 들어 있다
		String[] profiles = environment.getActiveProfiles().length > 0
				? environment.getActiveProfiles()
				: environment.getDefaultProfiles();

		return new ConsoleSnapshot.App(
				environment.getProperty("spring.application.name", "moeum"),
				List.of(profiles),
				System.getProperty("java.version"),
				ProcessHandle.current().pid(),
				runtime.getUptime(),
				LocalDateTime.ofInstant(Instant.ofEpochMilli(runtime.getStartTime()), clock.getZone()).format(STAMP),
				heap.getUsed() / MB,
				max / MB,
				(int) (heap.getUsed() * 100 / Math.max(max, 1)),
				ManagementFactory.getThreadMXBean().getThreadCount());
	}

	private ConsoleSnapshot.Pool pool() {
		try {
			HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
			HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
			if (mx == null) {
				return new ConsoleSnapshot.Pool(hikari.getPoolName(), 0, 0, 0, 0, hikari.getMaximumPoolSize());
			}
			return new ConsoleSnapshot.Pool(
					hikari.getPoolName(),
					mx.getActiveConnections(),
					mx.getIdleConnections(),
					mx.getTotalConnections(),
					mx.getThreadsAwaitingConnection(),
					hikari.getMaximumPoolSize());
		}
		catch (Exception e) {
			return new ConsoleSnapshot.Pool("unknown", 0, 0, 0, 0, 0);
		}
	}

	private ConsoleSnapshot.Db db() {
		try {
			Map<String, Object> row = jdbc.queryForMap(
					"SELECT VERSION() AS version, @@session.time_zone AS tz, "
							+ "DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s') AS server_now, "
							+ "@@max_connections AS max_conn, DATABASE() AS schema_name");

			long sizeBytes = single("SELECT COALESCE(SUM(data_length + index_length), 0) "
					+ "FROM information_schema.tables WHERE table_schema = DATABASE()", 0L);

			return new ConsoleSnapshot.Db(
					true, null,
					str(row.get("schema_name")),
					str(row.get("version")),
					str(row.get("tz")),
					str(row.get("server_now")),
					globalStatus("Uptime"),
					(int) globalStatus("Threads_connected"),
					Integer.parseInt(str(row.get("max_conn"))),
					sizeBytes / MB,
					globalStatus("Slow_queries"));
		}
		catch (Exception e) {
			log.debug("콘솔: DB 상태 조회 실패", e);
			return new ConsoleSnapshot.Db(false, rootMessage(e), null, null, null, null, 0, 0, 0, 0, 0);
		}
	}

	/** SHOW GLOBAL STATUS 한 항목. 권한이 없으면 0 */
	private long globalStatus(String name) {
		return safe(() -> {
			List<String> values = jdbc.query("SHOW GLOBAL STATUS LIKE ?",
					(rs, i) -> rs.getString("Value"), name);
			return values.isEmpty() ? 0L : Long.parseLong(values.get(0));
		}, 0L);
	}

	private ConsoleSnapshot.Flyway flyway() {
		if (!tableExists("flyway_schema_history")) {
			return noFlyway();
		}
		return safe(() -> {
			Map<String, Object> last = jdbc.queryForMap(
					"SELECT version, description, "
							+ "DATE_FORMAT(installed_on, '%Y-%m-%d %H:%i:%s') AS installed_on "
							+ "FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");

			return new ConsoleSnapshot.Flyway(true,
					str(last.get("version")),
					str(last.get("description")),
					single("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", 0L).intValue(),
					single("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", 0L).intValue(),
					str(last.get("installed_on")));
		}, noFlyway());
	}

	private ConsoleSnapshot.Flyway noFlyway() {
		return new ConsoleSnapshot.Flyway(false, null, null, 0, 0, null);
	}

	private ConsoleSnapshot.Pulse pulse() {
		return new ConsoleSnapshot.Pulse(
				countByStatus("payment"),
				countByStatus("order_group"),
				countByStatus("outbox"),
				single("SELECT COUNT(*) FROM payment WHERE status = 'CAPTURE_PENDING' "
						+ "AND updated_at < NOW(6) - INTERVAL ? MINUTE", 0L, CAPTURE_PENDING_STUCK_MINUTES),
				single("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING' AND next_attempt_at <= NOW(6)", 0L),
				single("SELECT COUNT(*) FROM outbox WHERE status = 'DEAD'", 0L),
				single("SELECT COUNT(*) FROM stock_hold WHERE status = 'HELD' AND expires_at < NOW(6)", 0L),
				single("SELECT COUNT(*) FROM refund WHERE status = 'PROCESSING'", 0L),
				single("SELECT COUNT(*) FROM payment WHERE status = 'CAPTURED' AND captured_at >= CURDATE()", 0L),
				// 환불한 만큼 뺀 값이다. 예전에는 payment.refunded_amount 를 뺐는데 그 컬럼은
				// 아무도 갱신하지 않아 빼도 빠지는 것이 없었고, 매출이 환불액만큼 부풀어 보였다.
				// 컬럼은 V17 에서 걷었다 — 기준은 COMPLETED 인 refund 행의 합이다 (D-060)
				single("SELECT COALESCE(SUM(p.amount - COALESCE(r.refunded, 0)), 0) FROM payment p "
						+ "LEFT JOIN (SELECT payment_id, SUM(amount) AS refunded FROM refund "
						+ "            WHERE status = 'COMPLETED' GROUP BY payment_id) r "
						+ "       ON r.payment_id = p.id "
						+ "WHERE p.status = 'CAPTURED' AND p.captured_at >= CURDATE()", 0L),
				single("SELECT COUNT(*) FROM sale_form WHERE status = 'SELLING'", 0L),
				recentEvents());
	}

	private List<Map<String, Object>> recentEvents() {
		return safe(() -> jdbc.queryForList(
				"SELECT e.id, e.payment_id, p.phase, g.order_no, e.from_status, e.to_status, "
						+ "e.actor, e.reason, DATE_FORMAT(e.created_at, '%m-%d %H:%i:%s') AS at "
						+ "FROM payment_event e "
						+ "JOIN payment p ON p.id = e.payment_id "
						+ "JOIN order_group g ON g.id = p.order_group_id "
						+ "ORDER BY e.id DESC LIMIT " + RECENT_EVENT_LIMIT), List.of());
	}

	private ConsoleSnapshot.Pulse emptyPulse() {
		return new ConsoleSnapshot.Pulse(Map.of(), Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, List.of());
	}

	/** status 컬럼을 가진 테이블의 상태별 건수 */
	private Map<String, Long> countByStatus(String table) {
		if (!tableExists(table)) {
			return Map.of();
		}
		return safe(() -> {
			Map<String, Long> result = new LinkedHashMap<>();
			jdbc.queryForList("SELECT status, COUNT(*) AS c FROM `" + table + "` "
							+ "GROUP BY status ORDER BY c DESC")
					.forEach(row -> result.put(str(row.get("status")), ((Number) row.get("c")).longValue()));
			return result;
		}, Map.of());
	}

	// ------------------------------------------------------------------
	// 판정 — 숫자를 보여주는 데서 멈추지 않는다
	// ------------------------------------------------------------------

	private List<Signal> signals(ConsoleSnapshot.App app, ConsoleSnapshot.Pool pool,
			ConsoleSnapshot.Db db, ConsoleSnapshot.Flyway flyway, ConsoleSnapshot.Pulse pulse) {
		List<Signal> signals = new ArrayList<>();

		if (!db.reachable()) {
			signals.add(Signal.risk("DB 에 붙지 못했다", db.error(),
					"docker compose up -d 로 moeum-mysql 이 떠 있는지 본다"));
			return signals;
		}
		if (flyway.failed() > 0) {
			signals.add(Signal.risk("마이그레이션 실패 기록 " + flyway.failed() + "건",
					"flyway_schema_history 에 success = 0 인 행이 있다",
					"실패한 행을 지우고 다시 올리거나 flyway repair 를 돌린다"));
		}
		if (pulse.capturePendingStuck() > 0) {
			signals.add(Signal.risk("승인 결과 미확정 결제 " + pulse.capturePendingStuck() + "건",
					CAPTURE_PENDING_STUCK_MINUTES + "분 넘게 CAPTURE_PENDING 이다. "
							+ "point3 응답을 못 받았거나 대사 배치가 멈췄다",
					"규칙 3 — 되돌리지 않는다. 대사 배치 로그부터 본다"));
		}
		if (pulse.outboxDead() > 0) {
			signals.add(Signal.risk("발송 포기된 이벤트 " + pulse.outboxDead() + "건",
					"outbox.status = DEAD. 알림이 나가지 않았다",
					"last_error 를 확인하고 원인을 고친 뒤 PENDING 으로 되돌린다"));
		}
		if (pool.waiting() > 0) {
			signals.add(Signal.risk("커넥션 풀 대기 " + pool.waiting() + "건",
					"활성 " + pool.active() + " / 최대 " + pool.max()
							+ ". 트랜잭션 안에서 외부 호출을 하고 있을 수 있다",
					"규칙 1 — @Transactional 안의 외부 API 호출을 찾는다"));
		}
		if (pulse.expiredHolds() > 0) {
			signals.add(Signal.warn("만료된 재고 홀드 " + pulse.expiredHolds() + "건",
					"expires_at 이 지났는데 아직 HELD 다. 그만큼 팔 수 있는 재고가 묶여 있다",
					"홀드 만료 배치가 도는지 본다. CAPTURE_PENDING 묶음을 건너뛰는 건 정상이다 (규칙 9)"));
		}
		if (pulse.outboxDue() > 0) {
			signals.add(Signal.warn("발송 대기 이벤트 " + pulse.outboxDue() + "건",
					"next_attempt_at 이 지난 PENDING. 릴레이가 집어가지 않고 있다",
					"릴레이 스케줄러가 살아 있는지 본다"));
		}
		if (pulse.refundProcessing() > 0) {
			signals.add(Signal.warn("처리 중 환불 " + pulse.refundProcessing() + "건",
					"refund.status = PROCESSING",
					"오래 남아 있으면 point3 환불 응답을 못 받은 것이다"));
		}
		if (app.heapPercent() >= HEAP_WARN_PERCENT) {
			signals.add(Signal.warn("힙 사용률 " + app.heapPercent() + "%",
					app.heapUsedMb() + "MB / " + app.heapMaxMb() + "MB",
					"덤프를 뜨거나 재기동한다"));
		}
		if (db.timeZone() != null && !"+09:00".equals(db.timeZone()) && !"Asia/Seoul".equals(db.timeZone())) {
			signals.add(Signal.warn("DB 타임존이 KST 가 아니다 (" + db.timeZone() + ")",
					"EOB 차단(23:30~00:30) 판정이 DB 시각에 걸려 있다",
					"docker-compose 의 --default-time-zone 을 확인한다"));
		}
		if (signals.isEmpty()) {
			signals.add(Signal.info("이상 신호 없음",
					"막힌 결제 · 죽은 이벤트 · 만료 홀드 · 풀 대기 모두 0", null));
		}
		return signals;
	}

	// ------------------------------------------------------------------
	// DB 탐색기
	// ------------------------------------------------------------------

	public List<TableSummary> tables() {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT table_name AS name, table_comment AS comment "
						+ "FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
						+ "ORDER BY table_name");

		List<TableSummary> result = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			String name = str(row.get("name"));
			// 근사치(information_schema.table_rows)는 InnoDB 에서 자주 0 으로 나온다.
			// 개발 DB 는 작으니 정확한 수를 센다.
			long count = safe(() -> single("SELECT COUNT(*) FROM `" + name + "`", 0L), 0L);
			long sizeKb = single("SELECT COALESCE(ROUND((data_length + index_length) / 1024), 0) "
					+ "FROM information_schema.tables "
					+ "WHERE table_schema = DATABASE() AND table_name = ?", 0L, name);
			result.add(new TableSummary(name, str(row.get("comment")), count, sizeKb));
		}
		return result;
	}

	/** ERD 화면용 — 테이블 목록과 외래키 관계를 한 번에 준다 */
	public ErdMap erd() {
		return new ErdMap(tables(), relations());
	}

	private List<ErdMap.Relation> relations() {
		return safe(() -> jdbc.query(
				"SELECT table_name, column_name, referenced_table_name, referenced_column_name, "
						+ "constraint_name "
						+ "FROM information_schema.key_column_usage "
						+ "WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL "
						+ "ORDER BY table_name, ordinal_position",
				(rs, i) -> new ErdMap.Relation(
						rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))),
				List.of());
	}

	public TablePeek peek(String table, int limit, int offset) {
		String safeTable = requireKnownTable(table);
		int rows = Math.min(Math.max(limit, 1), MAX_PEEK_ROWS);
		int skip = Math.max(offset, 0);

		List<Map<String, Object>> columnRows = jdbc.queryForList(
				"SELECT column_name AS name, column_type AS type, is_nullable AS nullable, "
						+ "column_key AS ckey, column_default AS cdefault, extra, "
						+ "column_comment AS comment "
						+ "FROM information_schema.columns "
						+ "WHERE table_schema = DATABASE() AND table_name = ? "
						+ "ORDER BY ordinal_position", safeTable);

		List<TablePeek.Column> columns = new ArrayList<>(columnRows.size());
		for (Map<String, Object> c : columnRows) {
			String name = str(c.get("name"));
			columns.add(new TablePeek.Column(
					name,
					str(c.get("type")),
					"YES".equalsIgnoreCase(str(c.get("nullable"))),
					str(c.get("ckey")),
					str(c.get("cdefault")),
					str(c.get("extra")),
					str(c.get("comment")),
					ValueMasker.masked(name)));
		}

		// 최근 행부터 본다. 기본키가 없는 테이블은 첫 컬럼으로 정렬한다
		String orderBy = columns.stream()
				.filter(c -> "PRI".equals(c.key()))
				.map(TablePeek.Column::name)
				.findFirst()
				.orElseGet(() -> columns.isEmpty() ? "1" : columns.get(0).name());

		QueryResult data = jdbc.query(
				"SELECT * FROM `" + safeTable + "` ORDER BY `" + orderBy + "` DESC "
						+ "LIMIT " + rows + " OFFSET " + skip,
				ValueMasker.extractor(rows));

		String comment = columns.isEmpty() ? "" : single(
				"SELECT table_comment FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_name = ?", "", safeTable);

		return new TablePeek(safeTable, comment, columns,
				data.columns(), data.rows(),
				single("SELECT COUNT(*) FROM `" + safeTable + "`", 0L),
				skip, rows, orderBy + " DESC");
	}

	/** 화이트리스트. 이 스키마에 실제로 있는 테이블 이름만 SQL 에 넣는다 */
	private String requireKnownTable(String table) {
		Long hit = jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' AND table_name = ?",
				Long.class, table);
		if (hit == null || hit == 0) {
			throw new IllegalArgumentException("그런 테이블이 없다: " + table);
		}
		return table;
	}

	private boolean tableExists(String table) {
		return safe(() -> {
			Long hit = jdbc.queryForObject(
					"SELECT COUNT(*) FROM information_schema.tables "
							+ "WHERE table_schema = DATABASE() AND table_name = ?",
					Long.class, table);
			return hit != null && hit > 0;
		}, false);
	}

	// ------------------------------------------------------------------
	// 주문 추적
	// ------------------------------------------------------------------

	/**
	 * 주문번호 · 토큰 · point3 sessionId · 묶음 id 중 무엇으로도 찾는다.
	 * 사고를 볼 때 손에 들고 있는 값이 매번 다르기 때문이다.
	 */
	public TraceResult trace(String query) {
		String q = query == null ? "" : query.trim();
		if (q.isEmpty()) {
			return TraceResult.notFound(q);
		}

		Long groupId = safe(() -> {
			List<Long> ids = jdbc.query(
					"SELECT g.id FROM order_group g "
							+ "LEFT JOIN payment p ON p.order_group_id = g.id "
							+ "WHERE g.order_no = ? OR g.order_token = ? OR g.session_token = ? "
							+ "   OR p.session_id = ? OR CAST(g.id AS CHAR) = ? "
							+ "ORDER BY g.id DESC LIMIT 1",
					(rs, i) -> rs.getLong(1), q, q, q, q, q);
			return ids.isEmpty() ? null : ids.get(0);
		}, null);

		if (groupId == null) {
			return TraceResult.notFound(q);
		}

		Map<String, Object> group = jdbc.queryForMap(
				"SELECT id, order_no, order_token, session_token, buyer_id, seller_id, "
						+ "deposit1_total, deposit2_total, shipping_fee, status, fail_reason, "
						+ "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
						+ "DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at, "
						+ "DATE_FORMAT(canceled_at, '%Y-%m-%d %H:%i:%s') AS canceled_at "
						+ "FROM order_group WHERE id = ?", groupId);

		Map<String, Object> buyer = safe(() -> jdbc.queryForMap(
				"SELECT id, nickname FROM buyer WHERE id = ?", group.get("buyer_id")), Map.of());
		Map<String, Object> seller = safe(() -> jdbc.queryForMap(
				"SELECT id, store_slug, review_status, shipping_fee, free_shipping_over "
						+ "FROM seller WHERE id = ?", group.get("seller_id")), Map.of());

		// refunded_amount 는 컬럼이 아니라 refund 행에서 센 값이다 — 화면 키는 그대로 둔다
		List<Map<String, Object>> payments = jdbc.queryForList(
				"SELECT p.id, p.phase, p.session_id, p.amount, p.status, p.fail_reason, "
						+ "COALESCE((SELECT SUM(r.amount) FROM refund r "
						+ "           WHERE r.payment_id = p.id AND r.status = 'COMPLETED'), 0) "
						+ "  AS refunded_amount, "
						+ "DATE_FORMAT(p.captured_at, '%Y-%m-%d %H:%i:%s') AS captured_at, "
						+ "DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
						+ "DATE_FORMAT(p.updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at "
						+ "FROM payment p WHERE p.order_group_id = ? ORDER BY p.id", groupId);

		List<Map<String, Object>> events = jdbc.queryForList(
				"SELECT e.id, e.payment_id, p.phase, e.from_status, e.to_status, e.actor, e.reason, "
						+ "DATE_FORMAT(e.created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
						+ "FROM payment_event e JOIN payment p ON p.id = e.payment_id "
						+ "WHERE p.order_group_id = ? ORDER BY e.id", groupId);

		List<Map<String, Object>> orders = jdbc.queryForList(
				"SELECT o.id, o.sale_form_id, f.title, o.qty, o.deposit1_sum, o.deposit2_sum, o.status "
						+ "FROM orders o JOIN sale_form f ON f.id = o.sale_form_id "
						+ "WHERE o.order_group_id = ? ORDER BY o.id", groupId);

		List<Map<String, Object>> holds = safe(() -> jdbc.queryForList(
				"SELECT h.id, h.order_id, h.sale_form_id, h.qty, h.status, "
						+ "DATE_FORMAT(h.expires_at, '%Y-%m-%d %H:%i:%s') AS expires_at, "
						+ "h.expires_at < NOW(6) AS expired "
						+ "FROM stock_hold h JOIN orders o ON o.id = h.order_id "
						+ "WHERE o.order_group_id = ? ORDER BY h.id", groupId), List.of());

		List<Map<String, Object>> refunds = safe(() -> jdbc.queryForList(
				"SELECT r.id, r.payment_id, r.order_id, r.amount, r.reason, r.requested_by, r.status, "
						+ "DATE_FORMAT(r.created_at, '%Y-%m-%d %H:%i:%s') AS created_at "
						+ "FROM refund r JOIN payment p ON p.id = r.payment_id "
						+ "WHERE p.order_group_id = ? ORDER BY r.id", groupId), List.of());

		List<Map<String, Object>> outbox = safe(() -> jdbc.queryForList(
				"SELECT id, aggregate_type, aggregate_id, event_type, status, retry_count, last_error, "
						+ "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS created_at, "
						+ "DATE_FORMAT(sent_at, '%Y-%m-%d %H:%i:%s') AS sent_at "
						+ "FROM outbox "
						+ "WHERE (aggregate_type = 'ORDER_GROUP' AND aggregate_id = ?) "
						+ "   OR (aggregate_type = 'PAYMENT' AND aggregate_id IN "
						+ "       (SELECT id FROM payment WHERE order_group_id = ?)) "
						+ "ORDER BY id", groupId, groupId), List.of());

		return new TraceResult(true, q, group, buyer, seller, orders, payments, events, refunds, holds, outbox);
	}

	// ------------------------------------------------------------------
	// 마이그레이션 이력
	// ------------------------------------------------------------------

	public List<Map<String, Object>> migrations() {
		if (!tableExists("flyway_schema_history")) {
			return List.of();
		}
		return jdbc.queryForList(
				"SELECT installed_rank, version, description, type, script, success, execution_time, "
						+ "DATE_FORMAT(installed_on, '%Y-%m-%d %H:%i:%s') AS installed_on "
						+ "FROM flyway_schema_history ORDER BY installed_rank DESC");
	}

	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private <T> T single(String sql, T fallback, Object... args) {
		try {
			T value = jdbc.queryForObject(sql, (Class<T>) fallback.getClass(), args);
			return value == null ? fallback : value;
		}
		catch (Exception e) {
			return fallback;
		}
	}

	/** 테이블이 아직 없거나 권한이 없어도 콘솔 전체가 죽지 않게 한다 */
	private <T> T safe(Supplier<T> supplier, T fallback) {
		try {
			return supplier.get();
		}
		catch (Exception e) {
			return fallback;
		}
	}

	private static String str(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static String rootMessage(Throwable e) {
		Throwable cursor = e;
		while (cursor.getCause() != null) {
			cursor = cursor.getCause();
		}
		return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
	}
}
