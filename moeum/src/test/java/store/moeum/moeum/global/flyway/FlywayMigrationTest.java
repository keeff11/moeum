package store.moeum.moeum.global.flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.support.IntegrationTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계 확인 조건: 앱이 뜨고 테이블이 전부 생성되어 있다.
 *
 * <p>여기서 기대값을 손으로 적지 않고 {@code docs/schema.sql} 을 읽는다.
 * 손으로 적으면 마이그레이션 · 문서 · 테스트 세 곳이 각자 늙는다.
 * 실제로 V3~V10 이 열 달치 쌓이는 동안 문서만 V1 에 멈춰 있었다.
 */
class FlywayMigrationTest extends IntegrationTest {

	/**
	 * Flyway 가 스스로 만드는 이력 테이블. 마이그레이션이 만든 것이 아니라
	 * 문서에도 없고 있어서도 안 된다.
	 */
	private static final Set<String> NOT_IN_DOC = Set.of("flyway_schema_history");

	private static final Pattern CREATE_TABLE =
			Pattern.compile("CREATE\\s+TABLE\\s+`?(\\w+)`?\\s*\\(", Pattern.CASE_INSENSITIVE);

	/** 테이블 본문에서 컬럼이 아닌 항목을 여는 낱말들. */
	private static final Set<String> NOT_A_COLUMN = Set.of(
			"PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "CHECK",
			"FULLTEXT", "SPATIAL");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("V1_마이그레이션이_성공으로_기록된다")
	void V1_마이그레이션이_성공으로_기록된다() {
		Boolean success = jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

		assertThat(success).isTrue();
	}

	/**
	 * 문서가 실제 스키마와 같은지 본다. 테이블 목록만 보지 않고 컬럼까지 보는 이유는,
	 * 지금까지 어긋난 아홉 건 중 테이블이 새로 생긴 것은 wishlist 하나뿐이고
	 * 나머지 여덟은 전부 컬럼 추가였기 때문이다. 테이블만 세면 그 여덟을 놓친다.
	 *
	 * <p>인덱스와 타입은 보지 않는다. 문서를 실행 가능한 DDL 로 유지하는 것이 목적이 아니라
	 * 읽는 사람이 없는 컬럼을 믿게 두지 않는 것이 목적이다.
	 */
	@Test
	@DisplayName("docs_schema_sql_이_실제_스키마와_일치한다")
	void docs_schema_sql_이_실제_스키마와_일치한다() {
		Map<String, Set<String>> documented = parseSchemaDoc();
		Map<String, Set<String>> actual = readActualSchema();

		assertThat(documented.keySet())
				.as("docs/schema.sql 의 테이블 목록. 마이그레이션을 더했으면 문서도 같이 고친다")
				.containsExactlyInAnyOrderElementsOf(actual.keySet());

		actual.forEach((table, columns) -> assertThat(documented.get(table))
				.as("docs/schema.sql 의 %s 컬럼", table)
				.containsExactlyInAnyOrderElementsOf(columns));
	}

	@Test
	@DisplayName("금액_컬럼은_정수_타입이다")
	void 금액_컬럼은_정수_타입이다() {
		List<String> floatingPointMoneyColumns = jdbcTemplate.queryForList("""
				SELECT CONCAT(table_name, '.', column_name)
				  FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				   AND data_type IN ('float', 'double', 'decimal')
				""", String.class);

		assertThat(floatingPointMoneyColumns).isEmpty();
	}

	private Map<String, Set<String>> readActualSchema() {
		Map<String, Set<String>> schema = new TreeMap<>();

		for (Map<String, Object> row : jdbcTemplate.queryForList("""
				SELECT table_name, column_name
				  FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				""")) {
			String table = String.valueOf(row.get("table_name")).toLowerCase();
			if (!NOT_IN_DOC.contains(table)) {
				schema.computeIfAbsent(table, t -> new LinkedHashSet<>())
						.add(String.valueOf(row.get("column_name")).toLowerCase());
			}
		}

		return schema;
	}

	private static Map<String, Set<String>> parseSchemaDoc() {
		String sql = stripComments(readSchemaDoc());
		Map<String, Set<String>> schema = new TreeMap<>();

		Matcher matcher = CREATE_TABLE.matcher(sql);
		while (matcher.find()) {
			String body = balancedBody(sql, matcher.end() - 1);
			schema.put(matcher.group(1).toLowerCase(), columnsOf(body));
		}

		return schema;
	}

	private static String readSchemaDoc() {
		// 테스트 작업 디렉터리는 gradle 프로젝트인 moeum/ 이지만, 저장소 루트에서 돌리는
		// 경우도 있어 docs/schema.sql 을 찾을 때까지 위로 올라간다.
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve("docs").resolve("schema.sql");
			if (Files.isRegularFile(candidate)) {
				try {
					return Files.readString(candidate, StandardCharsets.UTF_8);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		}
		throw new IllegalStateException("docs/schema.sql 을 찾지 못했다");
	}

	/**
	 * {@code -- } 줄 주석을 지운다. 문자열 리터럴 안의 {@code --} 는 건드리지 않고,
	 * 반대로 주석 안의 따옴표({@code '상품'의 실체})는 리터럴로 세지 않는다.
	 */
	private static String stripComments(String sql) {
		StringBuilder out = new StringBuilder(sql.length());
		boolean inString = false;

		for (int i = 0; i < sql.length(); i++) {
			char c = sql.charAt(i);

			if (inString) {
				out.append(c);
				if (c == '\\' && i + 1 < sql.length()) {
					out.append(sql.charAt(++i));
				} else if (c == '\'') {
					inString = false;
				}
			} else if (c == '\'') {
				inString = true;
				out.append(c);
			} else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
				while (i < sql.length() && sql.charAt(i) != '\n') {
					i++;
				}
				out.append('\n');
			} else {
				out.append(c);
			}
		}

		return out.toString();
	}

	/** {@code openIndex} 의 여는 괄호에 대응하는 닫는 괄호까지의 알맹이. */
	private static String balancedBody(String sql, int openIndex) {
		int depth = 0;
		boolean inString = false;

		for (int i = openIndex; i < sql.length(); i++) {
			char c = sql.charAt(i);

			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '\'') {
					inString = false;
				}
			} else if (c == '\'') {
				inString = true;
			} else if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return sql.substring(openIndex + 1, i);
			}
		}

		throw new IllegalStateException("괄호가 닫히지 않았다: " + sql.substring(openIndex,
				Math.min(sql.length(), openIndex + 60)));
	}

	/**
	 * 본문을 최상위 쉼표로 끊고 각 항목의 첫 낱말을 컬럼명으로 본다.
	 * 쉼표는 {@code COMMENT 'PENDING, APPROVED'} 안에도, 괄호 안에도 나오므로
	 * 문자열과 괄호 깊이를 함께 센다.
	 */
	private static Set<String> columnsOf(String body) {
		Set<String> columns = new LinkedHashSet<>();
		int depth = 0;
		boolean inString = false;
		int start = 0;

		for (int i = 0; i <= body.length(); i++) {
			if (i == body.length() || (!inString && depth == 0 && body.charAt(i) == ',')) {
				addColumn(columns, body.substring(start, i));
				start = i + 1;
				continue;
			}

			char c = body.charAt(i);
			if (inString) {
				if (c == '\\') {
					i++;
				} else if (c == '\'') {
					inString = false;
				}
			} else if (c == '\'') {
				inString = true;
			} else if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			}
		}

		return columns;
	}

	private static void addColumn(Set<String> columns, String definition) {
		String trimmed = definition.trim();
		if (trimmed.isEmpty()) {
			return;
		}

		String first = trimmed.split("\\s+", 2)[0].replace("`", "");
		if (!NOT_A_COLUMN.contains(first.toUpperCase())) {
			columns.add(first.toLowerCase());
		}
	}
}
