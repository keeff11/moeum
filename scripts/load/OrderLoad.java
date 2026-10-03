import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주문 만들기(재고 홀드) HTTP 부하 테스트. 로컬 앱(local 프로파일)에 대고 돌린다.
 *
 * JMeter · k6 없이 JDK 만으로 돈다 — 파일 하나를 그대로 실행한다 (Java 11+ 단일 파일 실행).
 *
 *   java scripts/load/OrderLoad.java                       # 1000명 · 재고 100
 *   java scripts/load/OrderLoad.java --users 1000 --stock 100000   # 품절 없이 전원 끝까지 간다
 *   java scripts/load/OrderLoad.java --keep --dump result.tsv       # 데이터를 남기고 구매자별 응답을 파일로
 *
 * 순서
 *   1. 이전 실행의 흔적을 지운다 (DELETE /dev/load — load- 접두어 행만)
 *   2. 판매 폼을 만들고 구매자 N 명을 로그인시킨다 (측정 밖)
 *   3. 워밍업 — JIT 가 덜 된 첫 요청들이 결과를 흐리지 않게 따로 몇 건 보낸다
 *   4. N 명이 같은 순간에 POST /checkout-sessions 를 보낸다 (측정)
 *   5. 응답 수와 DB 장부가 맞는지 확인한다. 어긋나면 종료 코드 1
 *
 * ★ 이 스크립트와 앱이 같은 PC 에서 돈다. 숫자는 운영 서버의 성능이 아니라
 *   "같은 구조에서 어디가 먼저 막히는가" 를 보는 용도다.
 */
public class OrderLoad {

	private static final int WARMUP_USER_BASE = 900_000;
	private static final Pattern NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");
	private static final Pattern CODE_FIELD = Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

	private static String base = "http://localhost:8080";
	private static int users = 1000;
	private static int stock = 100;
	private static int warmup = 30;
	private static boolean keep = false;
	private static String dump = null;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	record Result(String kakaoId, int status, String code, long millis) {
	}

	public static void main(String[] args) throws Exception {
		parseArgs(args);
		System.out.printf("대상 %s · 구매자 %d명 · 재고 %d%n%n", base, users, stock);

		send("DELETE", "/dev/load", null, null);

		// ---- 준비 (측정 밖)
		String warmupForm = send("POST", "/dev/load/sale-forms?stock=100000", null, null).body();
		String form = send("POST", "/dev/load/sale-forms?stock=" + stock, null, null).body();
		long saleFormId = number(form, "saleFormId");
		long optionId = number(form, "optionId");

		long loginStarted = System.nanoTime();
		List<String> cookies = loginAll(0, users);
		List<String> warmupCookies = loginAll(WARMUP_USER_BASE, warmup);
		System.out.printf("로그인 %d명 완료 (%dms)%n", users + warmup, msSince(loginStarted));

		// ---- 워밍업
		long warmupOption = number(warmupForm, "optionId");
		for (String cookie : warmupCookies) {
			order(null, cookie, warmupOption);
		}
		System.out.printf("워밍업 %d건 완료%n%n", warmup);

		// ---- 측정: N 명이 한꺼번에
		List<Result> results = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch ready = new CountDownLatch(users);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(users);   // 사용자마다 스레드 하나

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < cookies.size(); i++) {
			String kakaoId = "load-buyer-" + i;
			String cookie = cookies.get(i);
			futures.add(pool.submit(() -> {
				ready.countDown();
				try {
					start.await();
					results.add(order(kakaoId, cookie, optionId));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));
		}
		ready.await(60, TimeUnit.SECONDS);
		long started = System.nanoTime();
		start.countDown();
		for (Future<?> future : futures) {
			future.get();
		}
		long elapsed = msSince(started);
		pool.shutdown();

		boolean ok = report(results, elapsed, saleFormId);
		if (dump != null) {
			dumpResults(results);
		}

		if (!keep) {
			send("DELETE", "/dev/load", null, null);
		}
		System.exit(ok ? 0 : 1);
	}

	// ---------------------------------------------------------------- 요청

	private static Result order(String kakaoId, String cookie, long optionId) {
		String body = "{\"items\":[{\"optionId\":" + optionId + ",\"qty\":1}]}";
		long started = System.nanoTime();
		try {
			HttpResponse<String> response = send("POST", "/checkout-sessions", body, cookie);
			return new Result(kakaoId, response.statusCode(), codeOf(response), msSince(started));
		} catch (Exception e) {
			// 연결 거부 · 타임아웃은 응답이 없다. 예외 이름과 가장 안쪽 원인의 메시지로 묶는다 —
			// 서버가 거부한 것(Connection refused)과 이 PC 의 포트가 바닥난 것(Address already in use)을 가른다
			Throwable root = e;
			while (root.getCause() != null) {
				root = root.getCause();
			}
			String reason = root == e ? String.valueOf(e.getMessage())
					: root.getClass().getSimpleName() + ": " + root.getMessage();
			return new Result(kakaoId, -1, e.getClass().getSimpleName() + " (" + reason + ")", msSince(started));
		}
	}

	private static List<String> loginAll(int from, int count) throws Exception {
		// 로그인은 측정 대상이 아니라 적당히 나눠 보낸다
		ExecutorService pool = Executors.newFixedThreadPool(32);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = from; i < from + count; i++) {
				String kakaoId = "load-buyer-" + i;
				futures.add(pool.submit(() -> {
					HttpResponse<String> response = send("POST", "/dev/load/login?kakaoId=" + kakaoId, null, null);
					return response.headers().firstValue("Set-Cookie")
							.map(value -> value.split(";", 2)[0])
							.orElseThrow(() -> new IllegalStateException(
									"세션 쿠키가 없다. 앱이 local 프로파일로 떠 있는지 확인: " + response.statusCode()));
				}));
			}
			List<String> cookies = new ArrayList<>();
			for (Future<String> future : futures) {
				cookies.add(future.get());
			}
			return cookies;
		} finally {
			pool.shutdown();
		}
	}

	private static HttpResponse<String> send(String method, String path, String json, String cookie) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
				.timeout(Duration.ofSeconds(60))
				.method(method, json == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(json));
		if (json != null) {
			builder.header("Content-Type", "application/json");
		}
		if (cookie != null) {
			builder.header("Cookie", cookie);
		}
		HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		if (path.startsWith("/dev/") && response.statusCode() >= 400) {
			throw new IllegalStateException(method + " " + path + " → " + response.statusCode() + " " + response.body());
		}
		return response;
	}

	// ---------------------------------------------------------------- 결과

	private static boolean report(List<Result> results, long elapsedMs, long saleFormId) throws Exception {
		Map<String, Integer> byOutcome = new TreeMap<>();
		for (Result r : results) {
			String key = r.status() == -1 ? "연결 실패 " + r.code()
					: r.status() + (r.code() == null ? "" : " " + r.code());
			byOutcome.merge(key, 1, Integer::sum);
		}
		long created = results.stream().filter(r -> r.status() == 201).count();

		System.out.println("=== 결과 ===");
		System.out.printf("요청 %d건 · 전체 %dms · 처리량 %.1f건/초%n",
				results.size(), elapsedMs, results.size() * 1000.0 / Math.max(elapsedMs, 1));
		byOutcome.forEach((key, count) -> System.out.printf("  %-40s %5d%n", key, count));

		System.out.println();
		System.out.println("응답 시간(ms)          건수    p50    p90    p99    max");
		printLatency("전체", results);
		printLatency("201 주문 성공", results.stream().filter(r -> r.status() == 201).toList());
		printLatency("409 품절", results.stream().filter(r -> r.status() == 409).toList());
		printLatency("그 밖의 실패", results.stream()
				.filter(r -> r.status() != 201 && r.status() != 409).toList());

		String snapshot = send("GET", "/dev/load/sale-forms/" + saleFormId, null, null).body();
		long held = number(snapshot, "held");
		long sold = number(snapshot, "sold");
		long heldRows = number(snapshot, "heldRows");

		System.out.println();
		System.out.println("=== 장부 ===");
		System.out.printf("재고 %d · held %d · sold %d · HELD 홀드 행 %d · 201 응답 %d%n",
				stock, held, sold, heldRows, created);

		List<String> broken = new ArrayList<>();
		if (held + sold > stock) {
			broken.add("초과 판매: held + sold(" + (held + sold) + ") > 재고(" + stock + ")");
		}
		if (held != created) {
			broken.add("held(" + held + ") ≠ 201 응답 수(" + created + ")");
		}
		if (heldRows != created) {
			broken.add("홀드 행(" + heldRows + ") ≠ 201 응답 수(" + created + ")");
		}
		if (broken.isEmpty()) {
			System.out.println("장부 일치 ✔");
			return true;
		}
		broken.forEach(message -> System.out.println("✘ " + message));
		return false;
	}

	private static void printLatency(String label, List<Result> rows) {
		if (rows.isEmpty()) {
			return;
		}
		long[] sorted = rows.stream().mapToLong(Result::millis).sorted().toArray();
		System.out.printf("  %-18s %6d %6d %6d %6d %6d%n", label, sorted.length,
				percentile(sorted, 50), percentile(sorted, 90), percentile(sorted, 99), sorted[sorted.length - 1]);
	}

	private static long percentile(long[] sorted, int p) {
		int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
	}

	// ---------------------------------------------------------------- 보조

	/** 구매자별 응답을 남긴다. DB 의 실제 주문과 한 명씩 맞춰 볼 때 쓴다 (--dump 경로) */
	private static void dumpResults(List<Result> results) throws Exception {
		List<String> rows = new ArrayList<>();
		for (Result r : results) {
			rows.add(r.kakaoId() + "	" + r.status() + "	" + (r.code() == null ? "" : r.code()) + "	" + r.millis());
		}
		java.nio.file.Files.write(java.nio.file.Path.of(dump), rows);
		System.out.println("구매자별 응답: " + dump);
	}


	private static String codeOf(HttpResponse<String> response) {
		if (response.statusCode() < 400) {
			return null;
		}
		Matcher matcher = CODE_FIELD.matcher(response.body());
		return matcher.find() ? matcher.group(1) : null;
	}

	private static long number(String json, String field) {
		Matcher matcher = Pattern.compile(String.format(NUMBER_FIELD.pattern(), field)).matcher(json);
		if (!matcher.find()) {
			throw new IllegalStateException(field + " 가 응답에 없다: " + json);
		}
		return Long.parseLong(matcher.group(1));
	}

	private static long msSince(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

	private static void parseArgs(String[] args) {
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--base" -> base = args[++i];
				case "--users" -> users = Integer.parseInt(args[++i]);
				case "--stock" -> stock = Integer.parseInt(args[++i]);
				case "--warmup" -> warmup = Integer.parseInt(args[++i]);
				case "--keep" -> keep = true;
				case "--dump" -> dump = args[++i];
				default -> throw new IllegalArgumentException("모르는 인자: " + args[i]);
			}
		}
	}
}
