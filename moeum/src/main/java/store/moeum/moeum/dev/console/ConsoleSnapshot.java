package store.moeum.moeum.dev.console;

import java.util.List;
import java.util.Map;

/**
 * 콘솔 개요 화면이 한 번에 받아 가는 스냅샷.
 *
 * 화면이 여러 번 왕복하지 않게 서버·DB·도메인 지표를 한 응답에 담는다.
 * 폴링 주기가 짧아도 쿼리는 전부 인덱스를 타는 집계뿐이라 부담이 없다.
 */
public record ConsoleSnapshot(
		String takenAt,
		App app,
		Pool pool,
		Db db,
		Flyway flyway,
		Pulse pulse,
		List<Signal> signals
) {

	/** JVM 쪽 — 액추에이터를 열지 않고 MXBean 에서 직접 읽는다 */
	public record App(
			String name,
			List<String> profiles,
			String javaVersion,
			long pid,
			long uptimeMs,
			String startedAt,
			long heapUsedMb,
			long heapMaxMb,
			int heapPercent,
			int threads
	) {
	}

	/** 히카리 커넥션 풀. {@code waiting} 이 0 이 아니면 어딘가 커넥션을 붙잡고 있다는 뜻이다 */
	public record Pool(
			String name,
			int active,
			int idle,
			int total,
			int waiting,
			int max
	) {
	}

	/** MySQL 쪽. 붙지 않으면 {@code reachable=false} 로 내려가고 나머지는 비어 있다 */
	public record Db(
			boolean reachable,
			String error,
			String schema,
			String version,
			String timeZone,
			String serverNow,
			long uptimeSec,
			int connections,
			int maxConnections,
			long sizeMb,
			long slowQueries
	) {
	}

	/** flyway_schema_history 요약 */
	public record Flyway(
			boolean available,
			String current,
			String description,
			int applied,
			int failed,
			String lastAppliedAt
	) {
	}

	/** 도메인 맥박. 결제 플랫폼에서 눈을 떼면 안 되는 숫자들만 모았다 */
	public record Pulse(
			Map<String, Long> payment,
			Map<String, Long> orderGroup,
			Map<String, Long> outbox,
			long capturePendingStuck,
			long outboxDue,
			long outboxDead,
			long expiredHolds,
			long refundProcessing,
			long todayCapturedCount,
			long todayCapturedAmount,
			long sellingForms,
			List<Map<String, Object>> recentEvents
	) {
	}
}
