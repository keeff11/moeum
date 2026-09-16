package store.moeum.moeum.dev.console;

import java.util.List;

/** SQL 콘솔 실행 결과 */
public record QueryResult(
		List<String> columns,
		List<List<Object>> rows,
		int rowCount,
		boolean truncated,
		long elapsedMs,
		String sql
) {
}
