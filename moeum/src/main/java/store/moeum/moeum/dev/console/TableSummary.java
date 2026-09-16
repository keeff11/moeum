package store.moeum.moeum.dev.console;

/** DB 탐색기 좌측 목록의 한 줄 */
public record TableSummary(
		String name,
		String comment,
		long rows,
		long sizeKb
) {
}
