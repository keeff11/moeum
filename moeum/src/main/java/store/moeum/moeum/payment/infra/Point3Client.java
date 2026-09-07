package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import store.moeum.moeum.payment.exception.Point3Exception;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3RefundConflict;
import store.moeum.moeum.payment.exception.Point3RefundRejected;
import store.moeum.moeum.payment.exception.Point3UncertainException;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * point3 결제 API 호출.
 *
 * <b>이 클래스의 존재 이유는 4xx 와 5xx 를 나누는 것이다</b> (D-006, CLAUDE.md 규칙 2).
 * 하나로 잡으면 5xx·타임아웃이 실패로 처리되고, 실제로는 출금된 건의 홀드를 풀어
 * 그 재고를 남에게 팔게 된다. 구매자는 돈만 나간 상태가 된다.
 *
 * <pre>
 *   4xx            → Point3FailedException     확정 실패. 되돌려도 안전
 *   5xx · 타임아웃  → Point3UncertainException  결과 불명. 아무것도 되돌리지 않는다
 * </pre>
 *
 * <b>호출은 트랜잭션 밖에서 한다</b> (CLAUDE.md 규칙 1). point3 응답이 30초 걸리면
 * DB 락을 30초 잡는다. 이 클래스는 DB 를 전혀 건드리지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(Point3Properties.class)
public class Point3Client {

	/** 에러 본문에서 코드만 꺼내는 용도. 응답 매핑에는 쓰지 않는다 */
	private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

	private final Point3Properties properties;
	private final RestClient restClient;

	public Point3Client(Point3Properties properties) {
		this.properties = properties;

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());

		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(factory)
				.build();
	}

	/** 결제 세션 생성. 응답의 id 가 sessionId 이고, 저장에 실패하면 어느 주문인지 잃는다 */
	public Point3Session createSession(Point3SessionRequest request) {
		requireToken();

		return call("세션 생성", () -> restClient.post()
				.uri("/payment/v3/session")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectClientError)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.body(Point3Session.class));
	}

	/**
	 * 세션 조회. <b>부작용이 없다</b> — 대사 배치와 상태 조회 API 가 마음 놓고 반복해서 부른다 (D-014).
	 */
	public Point3Session getSession(String sessionId) {
		requireToken();

		return call("세션 조회", () -> restClient.get()
				.uri("/payment/v3/session/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectClientError)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.body(Point3Session.class));
	}

	/**
	 * 결제 승인 — <b>실제로 돈이 빠져나가는 호출이다.</b>
	 *
	 * 부르기 전에 {@code CAPTURE_PENDING} 을 커밋해 둬야 한다 (D-004).
	 * 그 기록이 없으면 여기서 서버가 죽었을 때 승인 여부를 추적할 방법이 없다.
	 *
	 * 멱등하다 — 타임아웃·5xx 뒤에 같은 호출을 다시 하거나 세션 조회로 확인하면 된다.
	 * payerId 는 전달하지 않는다 (point3-api 5절).
	 *
	 * 200 이 성공이 아니다. 응답 status 가 captured 인지 봐야 한다.
	 */
	public Point3Capture capture(String sessionId) {
		requireToken();

		return call("승인", () -> restClient.post()
				.uri("/capture/v2/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectClientError)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.body(Point3Capture.class));
	}

	// ---------------------------------------------------------------- 취소

	/**
	 * 결제 취소. <b>멱등하지 않다</b> — 승인과 결정적으로 다르다.
	 *
	 * 타임아웃이나 5xx 뒤에 같은 요청을 다시 보내면 <b>같은 취소가 두 번 실행된다</b>.
	 * 결과가 불확실하면 {@link #getRefund} 로 확인하고 {@link #resumeRefund} 로 마무리한다.
	 *
	 * 세금 3종을 호출자가 계산해서 넘겨야 한다 — point3 가 자동 계산하지 않는다 (D-011).
	 *
	 * @param idempotencyKey 24시간 동안 중복을 막는다. <b>하나의 논리적 취소에 하나만 만들어 저장한다.</b>
	 *                       재시도한다고 새 키로 바꾸면 이중 환불이다
	 */
	public Point3RefundEntry refund(String sessionId, Point3RefundRequest request, String idempotencyKey) {
		requireToken();

		return call("취소", () -> restClient.post()
				.uri("/refunds/v1/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.headers(headers -> {
					if (idempotencyKey != null && !idempotencyKey.isBlank()) {
						headers.set("Idempotency-Key", idempotencyKey);
					}
				})
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.onStatus(status -> status.value() == 409, Point3Client::rejectRefundConflict)
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectRefund)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.body(Point3RefundEntry.class));
	}

	/**
	 * 취소 상태 조회. <b>부작용이 없다</b> — 결과가 불확실할 때 진상을 알아내는 유일한 수단이다.
	 *
	 * <b>404 는 오류가 아니다.</b> "취소 정보를 찾을 수 없음" 이라 취소한 적 없는 결제를 조회하면
	 * 404 가 올 수 있다. 그래서 예외로 던지지 않고 빈 값으로 돌려준다 —
	 * 호출자는 "취소 이력 없음" 으로 다루면 된다.
	 */
	public Optional<Point3RefundStatus> getRefund(String sessionId) {
		requireToken();

		ResponseEntity<Point3RefundStatus> response = callNullable("취소 조회", () -> restClient.get()
				.uri("/refunds/v1/{sessionId}", sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.retrieve()
				.onStatus(status -> status.value() == 404, (req, res) -> {
					// 오류로 만들지 않는다. 아래에서 상태 코드를 보고 빈 값으로 바꾼다
				})
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectRefund)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.toEntity(Point3RefundStatus.class));

		// 본문을 그냥 역직렬화하면 404 의 에러 본문이 전 필드 null 인 객체가 되어 통과해 버린다.
		// 상태 코드를 직접 봐야 한다
		if (response.getStatusCode().value() == 404) {
			return Optional.empty();
		}
		return Optional.ofNullable(response.getBody());
	}

	/**
	 * 취소 처리 재개. <b>취소에서 유일하게 안전한 재시도 수단이다.</b>
	 *
	 * 새 취소를 만들지 않고 {@code processing} 인 항목을 이어서 끝낸다.
	 * 호출 전에 이미 완료됐어도 사고가 없다 — 재개하지 않고 최신 상태를 200 으로 돌려준다.
	 * 즉 여러 번 불러도 안전해서, 취소 대사 배치가 마음 놓고 반복할 수 있다.
	 *
	 * 조회로 처리 중인 취소를 확인한 뒤에만 부른다.
	 */
	public Point3RefundStatus resumeRefund(String sessionId) {
		requireToken();

		return call("취소 재개", () -> restClient.post()
				.uri("/refunds/v1/{sessionId}/resume", sessionId)
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.retrieve()
				.onStatus(HttpStatusCode::is4xxClientError, Point3Client::rejectRefund)
				.onStatus(HttpStatusCode::is5xxServerError, Point3Client::rejectServerError)
				.body(Point3RefundStatus.class));
	}

	/**
	 * 타임아웃 · 커넥션 끊김 · DNS 실패를 전부 '결과 불명' 으로 모은다.
	 *
	 * RestClientException 을 여기서 잡지 않으면 호출부가 RuntimeException 을 그대로 받고,
	 * 그걸 실패로 처리하는 순간 미수금이 된다.
	 */
	private <T> T call(String operation, Supplier<T> action) {
		try {
			T result = action.get();
			if (result == null) {
				// 200 인데 본문이 비었다. 무엇이 일어났는지 모르므로 되돌리지 않는다
				throw new Point3UncertainException("point3 " + operation + " 응답 본문이 비어 있다", null);
			}
			return result;
		} catch (Point3Exception e) {
			throw e;
		} catch (RestClientException e) {
			// 타임아웃이 여기로 온다. 승인이 이미 일어났을 수 있다
			log.error("point3 {} 통신 실패: {}", operation, e.getClass().getSimpleName());
			throw new Point3UncertainException("point3 " + operation + " 통신 실패", e);
		}
	}

	/** 본문이 없어도 되는 호출용. 404 를 정상으로 다루는 취소 조회가 여기 해당한다 */
	private <T> T callNullable(String operation, Supplier<T> action) {
		try {
			return action.get();
		} catch (Point3Exception e) {
			throw e;
		} catch (RestClientException e) {
			log.error("point3 {} 통신 실패: {}", operation, e.getClass().getSimpleName());
			throw new Point3UncertainException("point3 " + operation + " 통신 실패", e);
		}
	}

	private void requireToken() {
		if (!properties.isConfigured()) {
			// 토큰 발급 전이다. 확정 실패로 다뤄야 홀드가 풀리고 사용자가 다시 시도할 수 있다
			throw new Point3FailedException(0, "point3 API 토큰이 설정되지 않았다");
		}
	}

	private String bearer() {
		return "Bearer " + properties.apiToken();
	}

	private static void rejectClientError(HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		// 본문을 로그에 남기지 않는다 — 토큰·PIN·payerId 가 섞여 나올 수 있다 (point3-api 9절)
		log.warn("point3 4xx: status={}", status);
		throw new Point3FailedException(status, "point3 요청이 거부되었다 (status=" + status + ")");
	}

	/**
	 * 409 — {@code result.code} 로 처리가 갈린다 (point3-api 8절).
	 *
	 * 본문에서 <b>코드만</b> 꺼낸다. 전체 본문은 로그에도 예외 메시지에도 넣지 않는다 —
	 * 토큰·PIN·payerId 가 섞여 나올 수 있다 (point3-api 9절).
	 */
	private static void rejectRefundConflict(HttpRequest request, ClientHttpResponse response)
			throws IOException {
		RefundConflictCode code = readConflictCode(response);
		log.warn("point3 취소 409: code={}", code);
		throw new Point3RefundConflict(code);
	}

	/** 422 · 400 · 401 · 404 — 취소가 일어나지 않았다 */
	private static void rejectRefund(HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		log.warn("point3 취소 거절: status={}", status);
		throw new Point3RefundRejected(status, "point3 취소가 거절되었다 (status=" + status + ")");
	}

	/**
	 * 본문에서 {@code result.code} 만 꺼낸다.
	 *
	 * 파싱에 실패하면 UNKNOWN 이고, UNKNOWN 은 '모름' 으로 분류된다 —
	 * 코드를 못 읽었다고 확정 거절로 다루면 실제로 취소된 건을 실패 처리하게 된다.
	 */
	private static RefundConflictCode readConflictCode(ClientHttpResponse response) {
		try {
			JsonNode root = ERROR_MAPPER.readTree(response.getBody());
			JsonNode code = root.path("result").path("code");
			return RefundConflictCode.from(code.isTextual() ? code.asText() : null);
		} catch (Exception e) {
			log.warn("point3 취소 409 본문을 읽지 못했다: {}", e.getClass().getSimpleName());
			return RefundConflictCode.UNKNOWN;
		}
	}

	private static void rejectServerError(HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		log.error("point3 5xx: status={} — 되돌리지 않는다", status);
		throw new Point3UncertainException("point3 서버 오류 (status=" + status + ")", null);
	}
}
