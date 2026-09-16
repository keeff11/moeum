package store.moeum.moeum.dev.console;

import java.util.List;

/**
 * ERD 화면이 받아 가는 자료. 배치는 화면이 정하고, 서버는 <b>무엇이 무엇을 가리키는지</b>만 준다.
 *
 * 관계는 문서가 아니라 {@code information_schema} 에서 읽는다 — 실제로 DB 에 걸린 외래키라야
 * 화면에 선이 그어진다. 마이그레이션으로 관계가 하나 늘면 ERD 도 다음 새로고침에 따라온다.
 */
public record ErdMap(
		List<TableSummary> tables,
		List<Relation> relations
) {

	/** {@code from.fromColumn} 이 {@code to.toColumn} 을 가리킨다 (자식 → 부모) */
	public record Relation(
			String from,
			String fromColumn,
			String to,
			String toColumn,
			String constraintName
	) {
	}
}
