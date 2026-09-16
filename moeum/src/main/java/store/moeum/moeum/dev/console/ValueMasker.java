package store.moeum.moeum.dev.console;

import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 조회 결과를 화면에 실어도 되는 형태로 바꾼다.
 *
 * 두 가지를 한다.
 *   ① 암호화 컬럼({@code *_enc})과 비밀값은 값을 지운다 — 규칙 10.
 *      복호화 키가 서버에 있으니 콘솔이 마음만 먹으면 풀 수 있다. 풀지 않는 게 요점이다.
 *   ② VARBINARY · 긴 JSON 을 그대로 JSON 직렬화하면 화면이 죽는다. 요약해서 넘긴다.
 */
final class ValueMasker {

	private static final int MAX_TEXT = 300;
	private static final String MASK = "■■■ 암호화됨";

	private ValueMasker() {
	}

	/** 이 컬럼은 값을 보여주지 않는다 */
	static boolean masked(String column) {
		String name = column.toLowerCase(Locale.ROOT);
		return name.endsWith("_enc")
				|| name.contains("password")
				|| name.contains("secret")
				|| name.contains("api_token");
	}

	/**
	 * {@code SELECT *} 결과를 열 이름 + 행 배열로 뽑는다.
	 *
	 * @param limit 이 수를 넘으면 넘은 행은 버리고 {@code truncated} 를 세운다
	 */
	static ResultSetExtractor<QueryResult> extractor(int limit) {
		return rs -> {
			long started = System.nanoTime();
			ResultSetMetaData meta = rs.getMetaData();
			int width = meta.getColumnCount();

			List<String> columns = new ArrayList<>(width);
			boolean[] mask = new boolean[width];
			for (int i = 1; i <= width; i++) {
				String label = meta.getColumnLabel(i);
				columns.add(label);
				mask[i - 1] = masked(label);
			}

			List<List<Object>> rows = new ArrayList<>();
			boolean truncated = false;
			while (rs.next()) {
				if (rows.size() >= limit) {
					truncated = true;
					break;
				}
				List<Object> row = new ArrayList<>(width);
				for (int i = 1; i <= width; i++) {
					row.add(mask[i - 1] ? MASK : shrink(rs.getObject(i)));
				}
				rows.add(row);
			}

			return new QueryResult(columns, rows, rows.size(), truncated,
					(System.nanoTime() - started) / 1_000_000, null);
		};
	}

	/** 화면이 감당할 크기로 줄인다. 잘라낸 값에는 표시를 남긴다 */
	private static Object shrink(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof byte[] bytes) {
			return "binary " + bytes.length + "B";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value;
		}
		String text = String.valueOf(value);
		return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…(" + text.length() + ")";
	}
}
