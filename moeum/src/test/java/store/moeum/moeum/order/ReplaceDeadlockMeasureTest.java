package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 교체(다시 담기) 경로의 교착이 실제로 얼마나 나고, 재시도({@code @Retryable} 최대 3번)가 얼마나 받아 내는지 잰다.
 *
 * <b>교착이 나던 순서.</b> 주문 생성은 판매 폼을 id 오름차순으로 잡지만, 교체는 그 앞에
 * {@link CheckoutReplacer#releasePrevious} 로 이전 묶음의 폼을 먼저 고쳤다.
 * 철수가 B 를 잡아 둔 채 [A, B] 를 다시 담으면 B → A → B, 같은 순간 영희가 [A, B] 를 담으면 A → B 라
 * 서로 반대로 쥐었다. D-087 로 풀 폼과 잡을 폼을 합쳐 먼저 id 순으로 잠근다.
 *
 * <b>잰 값 (11-25, 로컬 Testcontainers, 커넥션 풀 10).</b>
 * 고치기 전: 1:1 × 300회에 교착 0~1번. 교체 10 + 신규 10 × 30회는 매 회차 교착(432~469번),
 * 신규 300명 중 136~171명이 마지막 3번째 시도에서 성공, 재시도를 다 쓰고 실패한 건 0.
 * 고친 뒤: 네 시나리오 모두 교착 0, 600건 전부 첫 시도에 성공.
 *
 * 타이밍을 강제로 맞추지 않는다 — 실제로 같은 순간에 눌렀을 때 자연스럽게 얼마나 겹치는지가 궁금한 것이다.
 * 판정은 잡음이 없게 MySQL 오류 코드로 한다(1213 교착, 1205 락 대기 초과).
 * 교착을 확정적으로 재현하는 회귀 테스트는 {@code CheckoutReplaceTest#교체_잠금_순서} 다.
 *
 * 측정용이라 평소 테스트에서는 돌지 않는다. {@code MOEUM_MEASURE=true} 일 때만 돈다.
 */
@EnabledIfEnvironmentVariable(named = "MOEUM_MEASURE", matches = "true")
class ReplaceDeadlockMeasureTest extends IntegrationTest {

	private static final int STOCK = 1_000_000;

	@Autowired
	private OrderService orderService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup formA;
	private OrderFixture.Setup formB;

	@BeforeEach
	void setUp() {
		fixture.clean();
		formA = fixture.saleForm(STOCK, null);
		formB = fixture.saleFormOfSameSeller(formA, STOCK);
		assertThat(formA.saleFormId()).isLessThan(formB.saleFormId());
	}

	@Test
	@DisplayName("1대1_교체하는_사람과_새로_담는_사람이_동시에_누를_때")
	void 일대일() throws Exception {
		measure("1:1 (교체 1 + 신규 1) × 300회", 300, 1, 1, formB);
	}

	@Test
	@DisplayName("몰릴_때_교체_10명과_신규_10명이_동시에_누를_때")
	void 몰릴_때() throws Exception {
		measure("몰림 (교체 10 + 신규 10) × 30회", 30, 10, 10, formB);
	}

	@Test
	@DisplayName("대조_신규_20명만_동시에_누를_때")
	void 대조_신규만() throws Exception {
		measure("대조: 신규 20명만 × 30회", 30, 0, 20, formB);
	}

	@Test
	@DisplayName("대조_교체하는_사람이_A_를_잡아_둔_경우_순서가_A_A_B_라_어긋나지_않는다")
	void 대조_순서가_맞는_교체() throws Exception {
		measure("대조: 교체 10(A 를 잡아 둠) + 신규 10 × 30회", 30, 10, 10, formA);
	}

	/** @param preHeld 교체할 사람이 미리 잡아 둘 폼. B 면 B → A → B 로 순서가 어긋나고, A 면 A → A → B 라 맞다 */
	private void measure(String title, int rounds, int replacers, int newcomers, OrderFixture.Setup preHeld)
			throws Exception {
		List<Outcome> outcomes = new ArrayList<>();
		int roundsWithDeadlock = 0;

		ExecutorService pool = Executors.newFixedThreadPool(replacers + newcomers);
		try {
			for (int round = 0; round < rounds; round++) {
				// 교체할 사람은 한 폼만 미리 잡아 둔다. [A, B] 를 담을 때 그 폼부터 고친다
				List<SessionUser> replacing = new ArrayList<>();
				for (int i = 0; i < replacers; i++) {
					SessionUser user = buyer("x-" + round + "-" + i);
					orderService.place(user, order(preHeld.optionId()));
					replacing.add(user);
				}

				CountDownLatch start = new CountDownLatch(1);
				List<Future<Outcome>> futures = new ArrayList<>();
				for (SessionUser user : replacing) {
					futures.add(pool.submit(() -> place("교체", user, start)));
				}
				for (int i = 0; i < newcomers; i++) {
					SessionUser user = buyer("y-" + round + "-" + i);
					futures.add(pool.submit(() -> place("신규", user, start)));
				}
				start.countDown();

				boolean deadlocked = false;
				for (Future<Outcome> future : futures) {
					Outcome outcome = future.get(60, TimeUnit.SECONDS);
					outcomes.add(outcome);
					deadlocked |= outcome.errors().contains("교착");
				}
				if (deadlocked) {
					roundsWithDeadlock++;
				}
			}
		} finally {
			pool.shutdownNow();
		}

		print(title, rounds, roundsWithDeadlock, outcomes);

		// 진 쪽이 롤백되어도 재고 숫자는 홀드와 맞아야 한다
		for (OrderFixture.Setup form : List.of(formA, formB)) {
			Integer held = jdbcTemplate.queryForObject("SELECT held FROM sale_form WHERE id = ?", Integer.class,
					form.saleFormId());
			Integer holds = jdbcTemplate.queryForObject(
					"SELECT COALESCE(SUM(qty), 0) FROM stock_hold WHERE sale_form_id = ? AND status = 'HELD'",
					Integer.class, form.saleFormId());
			assertThat(held).isEqualTo(holds);
		}
	}

	private Outcome place(String role, SessionUser user, CountDownLatch start) throws InterruptedException {
		start.await();
		Recorder.ERRORS.get().clear();
		String failure = null;
		try {
			orderService.place(user, new OrderCreateRequest(List.of(
					new OrderCreateRequest.Item(formA.optionId(), 1),
					new OrderCreateRequest.Item(formB.optionId(), 1))));
		} catch (Exception e) {
			failure = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		return new Outcome(role, List.copyOf(Recorder.ERRORS.get()), failure);
	}

	private void print(String title, int rounds, int roundsWithDeadlock, List<Outcome> outcomes) throws IOException {
		StringBuilder out = new StringBuilder("\n==== ").append(title).append(" ====\n");
		for (String role : List.of("교체", "신규", "전체")) {
			List<Outcome> mine = outcomes.stream().filter(o -> role.equals("전체") || o.role().equals(role)).toList();
			Map<String, Integer> byResult = new TreeMap<>();
			for (Outcome o : mine) {
				byResult.merge(o.label(), 1, Integer::sum);
			}
			long deadlocks = mine.stream().flatMap(o -> o.errors().stream()).filter("교착"::equals).count();
			long timeouts = mine.stream().flatMap(o -> o.errors().stream()).filter("락대기초과"::equals).count();
			out.append(String.format("[%s] 호출 %d건 | 교착 %d번 · 락대기초과 %d번 | %s%n",
					role, mine.size(), deadlocks, timeouts, byResult));
		}
		out.append(String.format("교착이 한 번이라도 난 회차: %d / %d%n", roundsWithDeadlock, rounds));
		outcomes.stream().map(Outcome::failure).filter(f -> f != null).distinct().limit(5)
				.forEach(f -> out.append("  실패 예: ").append(f).append('\n'));
		// Gradle 결과 XML 은 Windows 에서 한글이 깨져서 UTF-8 파일로 따로 남긴다
		Files.writeString(REPORT, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static final Path REPORT = Path.of("build", "replace-deadlock-measure.txt");

	private SessionUser buyer(String kakaoId) {
		return new SessionUser(kakaoId, "구매자");
	}

	private OrderCreateRequest order(Long optionId) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, 1)));
	}

	/**
	 * @param errors 시도마다 난 오류. 비어 있으면 첫 시도에 끝났다
	 * @param failure 재시도까지 다 쓰고도 실패했으면 그 예외
	 */
	record Outcome(String role, List<String> errors, String failure) {

		String label() {
			if (failure != null) {
				return "실패(" + errors.size() + "번 시도)";
			}
			return errors.isEmpty() ? "1번에 성공" : (errors.size() + 1) + "번째에 성공";
		}
	}

	/** {@code @Retryable} 은 RetryListener 빈을 전부 붙인다. 재시도는 부른 스레드에서 돌아 ThreadLocal 로 모은다 */
	@TestConfiguration
	static class Recorder {

		static final ThreadLocal<List<String>> ERRORS = ThreadLocal.withInitial(ArrayList::new);

		@Bean
		RetryListener errorRecorder() {
			return new RetryListener() {
				@Override
				public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
				                                             Throwable throwable) {
					ERRORS.get().add(kindOf(throwable));
				}
			};
		}

		private static String kindOf(Throwable throwable) {
			for (Throwable t = throwable; t != null; t = t.getCause()) {
				if (t instanceof SQLException sql) {
					if (sql.getErrorCode() == 1213) {
						return "교착";
					}
					if (sql.getErrorCode() == 1205) {
						return "락대기초과";
					}
				}
			}
			return throwable.getClass().getSimpleName();
		}
	}
}
