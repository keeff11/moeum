package store.moeum.moeum.payment.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3UncertainException;

import java.io.IOException;
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
		} catch (Point3FailedException | Point3UncertainException e) {
			throw e;
		} catch (RestClientException e) {
			// 타임아웃이 여기로 온다. 승인이 이미 일어났을 수 있다
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

	private static void rejectServerError(HttpRequest request, ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		log.error("point3 5xx: status={} — 되돌리지 않는다", status);
		throw new Point3UncertainException("point3 서버 오류 (status=" + status + ")", null);
	}
}
