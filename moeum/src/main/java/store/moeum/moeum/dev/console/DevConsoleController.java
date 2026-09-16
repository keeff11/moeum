package store.moeum.moeum.dev.console;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 백엔드 개발자용 운영 콘솔. <b>로컬 프로파일에서만 등록된다.</b>
 *
 * 화면은 {@code /dev/console.html} 이고 이 컨트롤러는 그 화면의 데이터원이다.
 * prod 프로파일에서는 빈이 아예 만들어지지 않으므로 운영에는 이 경로가 없다.
 */
@Tag(name = "개발 도구(local 전용)", description = "운영 콘솔. 읽기 전용이며 운영에는 등록되지 않는다")
@RestController
@RequestMapping("/dev/console")
@Profile("local")
@RequiredArgsConstructor
@Validated
public class DevConsoleController {

	private final DevConsoleService console;
	private final SqlConsoleService sql;
	private final RoadmapReader roadmapReader;

	@Operation(summary = "개요 스냅샷", description = "앱 · 커넥션 풀 · DB · 마이그레이션 · 도메인 지표와 판정")
	@GetMapping("/snapshot")
	public ConsoleSnapshot snapshot() {
		return console.snapshot();
	}

	@Operation(summary = "테이블 목록", description = "이름 · 주석 · 정확한 행 수 · 용량")
	@GetMapping("/tables")
	public List<TableSummary> tables() {
		return console.tables();
	}

	@Operation(summary = "ERD", description = "테이블 목록과 실제로 DB 에 걸린 외래키 관계")
	@GetMapping("/erd")
	public ErdMap erd() {
		return console.erd();
	}

	@Operation(summary = "개발 진척", description = "docs/status.html 의 총계와 도메인별 요약")
	@GetMapping("/roadmap")
	public RoadmapProgress roadmap() {
		return roadmapReader.read();
	}

	@Operation(summary = "개발 현황판 원본", description = "docs/status.html 을 그대로 연다")
	@GetMapping(value = "/status-board", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> statusBoard() throws IOException {
		return ResponseEntity.ok(roadmapReader.html());
	}

	@Operation(summary = "테이블 훑어보기", description = "컬럼 스키마와 최근 행. 암호화 컬럼은 값을 내리지 않는다")
	@GetMapping("/tables/{name}")
	public TablePeek peek(
			@PathVariable String name,
			@RequestParam(defaultValue = "30") @Min(1) @Max(200) int limit,
			@RequestParam(defaultValue = "0") @Min(0) int offset) {
		return console.peek(name, limit, offset);
	}

	@Operation(summary = "SQL 실행", description = "SELECT · SHOW · EXPLAIN 만. 커넥션을 read-only 로 연다")
	@PostMapping("/query")
	public QueryResult query(@RequestBody QueryRequest request) {
		return sql.run(request.sql(), request.limit() == null ? 100 : request.limit());
	}

	@Operation(summary = "진단 쿼리 모음", description = "자주 쓰는 점검 쿼리")
	@GetMapping("/presets")
	public List<SqlConsoleService.Preset> presets() {
		return sql.presets();
	}

	@Operation(summary = "주문 추적", description = "주문번호 · 토큰 · point3 sessionId · 묶음 id 중 아무거나")
	@GetMapping("/trace")
	public TraceResult trace(@RequestParam String q) {
		return console.trace(q);
	}

	@Operation(summary = "마이그레이션 이력", description = "flyway_schema_history")
	@GetMapping("/migrations")
	public List<Map<String, Object>> migrations() {
		return console.migrations();
	}

	public record QueryRequest(String sql, Integer limit) {
	}

	/**
	 * 콘솔 에러는 화면에 그대로 보여 준다.
	 * 전역 핸들러의 코드 체계에 억지로 끼워 맞추면 SQL 문법 오류가 무슨 말인지 알 수 없어진다.
	 */
	@ExceptionHandler({ IllegalArgumentException.class, IOException.class,
			org.springframework.dao.DataAccessException.class })
	public ResponseEntity<Map<String, String>> handle(Exception e) {
		return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
	}
}
