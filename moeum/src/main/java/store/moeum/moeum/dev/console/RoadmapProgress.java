package store.moeum.moeum.dev.console;

import java.util.List;

/**
 * {@code docs/status.html} 에서 읽어 온 개발 진척.
 *
 * 콘솔은 총계와 도메인 한 줄까지만 보여 준다. 항목별 상세는 현황판이 이미 잘 하고 있으니
 * 같은 표를 두 벌 유지하지 않는다 — 도메인 이름을 누르면 현황판의 그 자리로 보낸다.
 */
public record RoadmapProgress(
		boolean available,
		String source,
		int total,
		String asOf,
		List<Domain> domains
) {

	public record Domain(
			String name,
			/** 현황판의 details 요소 id. 링크는 {@code status-board#슬러그} 로 건다 */
			String slug,
			int pct,
			int weight,
			int done,
			int part,
			int check,
			int todo
	) {

		public int items() {
			return done + part + check + todo;
		}
	}

	public static RoadmapProgress unavailable(String reason) {
		return new RoadmapProgress(false, reason, 0, null, List.of());
	}
}
