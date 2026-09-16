package store.moeum.moeum.dev.console;

import java.util.List;

/** 테이블 하나의 스키마와 최근 행 */
public record TablePeek(
		String table,
		String comment,
		List<Column> columns,
		List<String> resultColumns,
		List<List<Object>> rows,
		long totalRows,
		int offset,
		int limit,
		String orderBy
) {

	public record Column(
			String name,
			String type,
			boolean nullable,
			String key,
			String defaultValue,
			String extra,
			String comment,
			boolean masked
	) {
	}
}
