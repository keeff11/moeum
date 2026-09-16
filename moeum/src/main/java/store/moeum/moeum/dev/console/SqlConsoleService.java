package store.moeum.moeum.dev.console;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 콘솔의 SQL 창. <b>읽기만 된다.</b>
 *
 * 막는 층을 두 개 둔다.
 *   ① 첫 낱말 화이트리스트와 문장 1개 제한 — 오타로 UPDATE 를 날리는 사고를 앞에서 막는다
 *   ② 커넥션을 read-only 로 열어 MySQL 이 직접 거절하게 한다 —
 *      주석이나 대소문자로 ①을 피해 가도 여기서 걸린다
 *
 * 로컬 전용이지만 개발 DB 에도 되살리기 곤란한 데이터가 있다.
 * "실수로 지웠다"가 성립하지 않게 쓰기 경로를 아예 만들지 않는다.
 */
@Service
@Profile("local")
@RequiredArgsConstructor
public class SqlConsoleService {

	private static final Set<String> ALLOWED_HEADS =
			Set.of("select", "with", "show", "explain", "desc", "describe");

	private static final int MAX_ROWS = 500;
	private static final int TIMEOUT_SECONDS = 5;

	private final DataSource dataSource;

	public QueryResult run(String rawSql, int limit) {
		String sql = normalize(rawSql);
		int rows = Math.min(Math.max(limit, 1), MAX_ROWS);
		long started = System.nanoTime();

		try (Connection conn = dataSource.getConnection()) {
			conn.setReadOnly(true);
			try (Statement st = conn.createStatement()) {
				st.setQueryTimeout(TIMEOUT_SECONDS);
				// 한 행 더 가져와서 "잘렸다"를 판정한다
				st.setMaxRows(rows + 1);
				try (ResultSet rs = st.executeQuery(sql)) {
					QueryResult raw = ValueMasker.extractor(rows).extractData(rs);
					return new QueryResult(
							raw.columns(), raw.rows(), raw.rowCount(), raw.truncated(),
							(System.nanoTime() - started) / 1_000_000, sql);
				}
			}
			finally {
				conn.setReadOnly(false);
			}
		}
		catch (SQLException e) {
			throw new IllegalArgumentException(message(e), e);
		}
	}

	/** 자주 쓰는 진단 쿼리. 화면 좌측에 그대로 뿌린다 */
	public List<Preset> presets() {
		return List.of(
				new Preset("승인 결과 미확정 결제",
						"CAPTURE_PENDING 으로 5분 넘게 남아 있는 건. 규칙 3 — 되돌리지 않는다",
						"""
						SELECT p.id, p.phase, p.amount, g.order_no, g.status AS group_status,
						       TIMESTAMPDIFF(MINUTE, p.updated_at, NOW()) AS stuck_min
						  FROM payment p JOIN order_group g ON g.id = p.order_group_id
						 WHERE p.status = 'CAPTURE_PENDING'
						 ORDER BY p.updated_at"""),

				new Preset("만료됐는데 안 풀린 홀드",
						"expires_at 이 지났는데 HELD. 그만큼 재고가 묶여 있다",
						"""
						SELECT h.id, h.sale_form_id, h.qty, h.expires_at, g.status AS group_status
						  FROM stock_hold h
						  JOIN orders o      ON o.id = h.order_id
						  JOIN order_group g ON g.id = o.order_group_id
						 WHERE h.status = 'HELD' AND h.expires_at < NOW()
						 ORDER BY h.expires_at"""),

				new Preset("재고 정합성",
						"held + sold 가 stock_max 를 넘은 폼이 있으면 초과 판매다",
						"""
						SELECT id, title, stock_max, held, sold,
						       stock_max - held - sold AS remaining
						  FROM sale_form
						 WHERE held + sold > stock_max
						    OR held < 0 OR sold < 0"""),

				new Preset("옵션 재고 정합성",
						"옵션 held + sold 가 stock_max 를 넘었거나, 옵션 합계가 폼 카운터와 어긋난 폼 (D-054)",
						"""
						SELECT o.id AS option_id, o.name, o.stock_max, o.held, o.sold,
						       f.id AS sale_form_id, f.held AS form_held, f.sold AS form_sold
						  FROM product_option o
						  JOIN product p    ON p.id = o.product_id
						  JOIN sale_form f  ON f.id = p.sale_form_id
						 WHERE (o.stock_max IS NOT NULL AND o.held + o.sold > o.stock_max)
						    OR o.held < 0 OR o.sold < 0
						 UNION ALL
						SELECT NULL, '(합계 불일치)', NULL, SUM(o.held), SUM(o.sold),
						       f.id, f.held, f.sold
						  FROM sale_form f
						  JOIN product p        ON p.sale_form_id = f.id
						  JOIN product_option o ON o.product_id = p.id
						 GROUP BY f.id, f.held, f.sold
						HAVING SUM(o.held) <> f.held OR SUM(o.sold) <> f.sold"""),

				new Preset("막힌 아웃박스",
						"DEAD 이거나 재시도가 쌓인 이벤트",
						"""
						SELECT id, aggregate_type, aggregate_id, event_type, status,
						       retry_count, next_attempt_at, last_error
						  FROM outbox
						 WHERE status = 'DEAD' OR retry_count > 0
						 ORDER BY retry_count DESC, id DESC"""),

				new Preset("오늘 결제",
						"승인된 결제를 시간순으로",
						"""
						SELECT p.id, p.phase, p.amount,
						       COALESCE((SELECT SUM(r.amount) FROM refund r
						                  WHERE r.payment_id = p.id
						                    AND r.status = 'COMPLETED'), 0) AS refunded_amount,
						       p.captured_at, g.order_no, s.store_slug
						  FROM payment p
						  JOIN order_group g ON g.id = p.order_group_id
						  JOIN seller s      ON s.id = g.seller_id
						 WHERE p.captured_at >= CURDATE()
						 ORDER BY p.captured_at DESC"""),

				new Preset("결제 없이 남은 묶음",
						"PAY_PENDING 인데 payment 행이 없다면 결제 시작 경로가 끊긴 것이다",
						"""
						SELECT g.id, g.order_no, g.status, g.deposit1_total, g.created_at
						  FROM order_group g
						  LEFT JOIN payment p ON p.order_group_id = g.id
						 WHERE g.status IN ('PAY_PENDING', 'CONFIRMING') AND p.id IS NULL
						 ORDER BY g.created_at DESC"""),

				new Preset("느린 테이블",
						"행 수와 용량. 인덱스가 데이터보다 크면 한 번 본다",
						"""
						SELECT table_name, table_rows,
						       ROUND(data_length  / 1024) AS data_kb,
						       ROUND(index_length / 1024) AS index_kb
						  FROM information_schema.tables
						 WHERE table_schema = DATABASE()
						 ORDER BY data_length + index_length DESC"""),

				new Preset("지금 도는 쿼리",
						"1초 넘게 실행 중인 세션. 락이 걸렸을 때 여기부터 본다",
						"""
						SELECT id, user, db, command, time, state, LEFT(info, 200) AS query
						  FROM information_schema.processlist
						 WHERE command <> 'Sleep' AND time >= 1
						 ORDER BY time DESC""")
		);
	}

	public record Preset(String title, String note, String sql) {
	}

	/**
	 * 문장 하나인지, 읽기로 시작하는지 본다.
	 * 통과해도 커넥션이 read-only 라 쓰기는 MySQL 이 다시 거절한다.
	 */
	private String normalize(String rawSql) {
		if (rawSql == null || rawSql.isBlank()) {
			throw new IllegalArgumentException("실행할 SQL 이 비어 있다");
		}
		String sql = rawSql.strip();
		while (sql.endsWith(";")) {
			sql = sql.substring(0, sql.length() - 1).strip();
		}
		if (sql.contains(";")) {
			throw new IllegalArgumentException("한 번에 한 문장만 실행한다");
		}

		String head = sql.split("[\\s(]", 2)[0].toLowerCase(Locale.ROOT);
		if (!ALLOWED_HEADS.contains(head)) {
			throw new IllegalArgumentException(
					"읽기 쿼리만 실행한다 (" + String.join(", ", ALLOWED_HEADS) + "). 받은 것: " + head);
		}
		return sql;
	}

	private String message(SQLException e) {
		String text = e.getMessage() == null ? e.toString() : e.getMessage();
		// read-only 커넥션이 거절한 경우를 사람 말로 바꿔 준다
		if (text.contains("read-only") || text.contains("READ ONLY")) {
			return "이 콘솔은 읽기 전용이다. 쓰기는 마이그레이션이나 애플리케이션 경로로 한다";
		}
		return text;
	}
}
