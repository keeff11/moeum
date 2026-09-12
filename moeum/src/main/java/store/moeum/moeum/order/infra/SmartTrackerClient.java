package store.moeum.moeum.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * 스마트택배(스윗트래커) 배송조회.
 *
 * <pre>
 *   GET /api/v1/companylist?t_key=                        택배사 목록
 *   GET /api/v1/trackingInfo?t_key=&amp;t_code=&amp;t_invoice=    배송 추적
 * </pre>
 *
 * <b>200 이 성공이 아니다.</b> 조회에 실패해도 HTTP 200 에 본문으로
 * {@code {"status": false, "msg": ..., "code": ...}} 가 온다 (Swagger 의 {@code Status} 모델).
 * 상태 코드만 보면 <b>빈 배송 정보를 정상 조회로 치고 화면에 "배송 정보 없음" 대신
 * 빈 화면을 띄운다.</b> SOLAPI 에서 겪은 것과 같은 모양이다 (D-040).
 *
 * <b>응답을 {@code JsonNode} 로 받는다.</b> 이 API 는 성공과 실패가 <b>다른 스키마</b>로
 * 오고(추적 객체 / Status 객체), 필드도 계속 늘어난다. 레코드로 고정하면 모르는 필드가
 * 하나 늘 때마다 파싱이 깨진다 — 우리가 쓰는 몇 개만 꺼내 쓴다.
 *
 * <b>호출은 트랜잭션 밖에서 한다</b> (CLAUDE.md 규칙 1). 이 클래스는 DB 를 건드리지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SmartTrackerProperties.class)
public class SmartTrackerClient {

	private static final String COMPANY_LIST_PATH = "/api/v1/companylist";
	private static final String TRACKING_PATH = "/api/v1/trackingInfo";

	private final SmartTrackerProperties properties;
	private final RestClient restClient;

	public SmartTrackerClient(SmartTrackerProperties properties) {
		this.properties = properties;

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout((int) properties.connectTimeout().toMillis());
		factory.setReadTimeout((int) properties.readTimeout().toMillis());

		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(factory)
				.build();

		if (properties.isConfigured() && !properties.isEnabled()) {
			// 키를 넣었는데 안 나가면 설정이 잘못된 줄 알기 쉽다. 일부러 꺼 둔 것임을 남긴다
			log.info("배송조회 키는 있지만 꺼져 있다. moeum.tracking.enabled=true 로 켠다");
		}
	}

	/** 실제로 조회를 부를 수 있는가. 키와 {@code enabled} 스위치가 둘 다 있어야 한다 */
	public boolean isEnabled() {
		return properties.isEnabled();
	}

	/**
	 * 택배사 목록. 송장 등록 화면의 선택지가 된다.
	 *
	 * 우리가 코드 목록을 들고 있지 않는 이유다 — 택배사는 늘고 코드는 그쪽이 정한다.
	 * 화면이 이 목록에서 고르게 하면 <b>등록되는 코드가 항상 조회에 쓸 수 있는 코드</b>다.
	 */
	public List<Carrier> carriers() {
		JsonNode body = get(COMPANY_LIST_PATH, uri -> uri.queryParam("t_key", properties.apiKey()));

		// 응답이 {"Company":[...]} 로 감싸여 오는지 배열로 바로 오는지는 계정/버전에 따라 다르다.
		// 둘 다 받는다 — 여기서 틀리면 목록이 통째로 비고, 그때 셀러는 택배사를 고를 수 없다
		JsonNode array = body.isArray() ? body : firstArrayOf(body);
		if (array == null) {
			throw TrackingException.internal("택배사 목록을 읽을 수 없다: " + body);
		}

		List<Carrier> carriers = new ArrayList<>();
		for (JsonNode node : array) {
			String code = text(node, "Code", "code");
			String name = text(node, "Name", "name");
			if (code != null && name != null) {
				carriers.add(new Carrier(code, name));
			}
		}
		return carriers;
	}

	/**
	 * 배송 추적.
	 *
	 * @param carrierCode 택배사 코드 (t_code). {@link #carriers()} 가 준 값이다
	 * @param trackingNo  송장번호 (t_invoice)
	 */
	public Tracking track(String carrierCode, String trackingNo) {
		JsonNode body = get(TRACKING_PATH, uri -> uri
				.queryParam("t_key", properties.apiKey())
				.queryParam("t_code", carrierCode)
				.queryParam("t_invoice", trackingNo));

		// 실패는 200 + {"status":false,...} 로 온다. 성공 응답에는 이 필드가 없거나 true 다
		JsonNode status = body.get("status");
		if (status != null && status.isBoolean() && !status.asBoolean()) {
			String message = body.path("msg").asText("조회할 수 없는 송장이다");
			log.info("배송조회 실패: code={}, msg={}", body.path("code").asText(""), message);
			throw TrackingException.ofProvider(message);
		}

		List<Tracking.Step> steps = new ArrayList<>();
		for (JsonNode node : body.path("trackingDetails")) {
			steps.add(new Tracking.Step(
					text(node, "timeString", "time"),
					text(node, "where"),
					text(node, "kind"),
					node.path("level").asInt(0)));
		}

		return new Tracking(
				text(body, "invoiceNo"),
				body.path("level").asInt(0),
				body.path("complete").asBoolean(false) || "Y".equalsIgnoreCase(text(body, "completeYN")),
				steps);
	}

	private JsonNode get(String path, java.util.function.UnaryOperator<org.springframework.web.util.UriBuilder> query) {
		if (!properties.isEnabled()) {
			// 부르는 쪽이 이미 걸렀어야 한다. 여기까지 오면 호출 지점을 하나 빠뜨린 것이다
			throw TrackingException.internal("배송조회가 꺼져 있다");
		}

		try {
			JsonNode body = restClient.get()
					.uri(uri -> query.apply(uri.path(path)).build())
					.retrieve()
					.body(JsonNode.class);

			if (body == null) {
				throw TrackingException.internal("배송조회 응답이 비어 있다");
			}
			return body;

		} catch (RestClientException e) {
			// 4xx · 5xx · 타임아웃을 나누지 않는다 ({@link TrackingException} 참고)
			log.warn("배송조회 호출 실패: path={}", path, e);
			throw TrackingException.internal("배송조회 호출 실패", e);
		}
	}

	/** 감싸는 키 이름이 무엇이든 첫 번째 배열을 집는다 */
	private static JsonNode firstArrayOf(JsonNode body) {
		for (JsonNode child : body) {
			if (child.isArray()) {
				return child;
			}
		}
		return null;
	}

	private static String text(JsonNode node, String... names) {
		for (String name : names) {
			JsonNode value = node.get(name);
			if (value != null && !value.isNull() && !value.asText().isBlank()) {
				return value.asText();
			}
		}
		return null;
	}

	/**
	 * @param code 스마트택배 택배사 코드. shipping.carrier_code 에 그대로 들어간다
	 * @param name 화면에 보이는 이름
	 */
	public record Carrier(String code, String name) {
	}

	/**
	 * @param invoiceNo 송장번호
	 * @param level     진행 단계 (1 배송준비 ~ 6 배송완료)
	 * @param completed 배송이 끝났는가
	 * @param steps     단계별 기록. 오래된 것부터다
	 */
	public record Tracking(String invoiceNo, int level, boolean completed, List<Step> steps) {

		/**
		 * @param time  택배사가 준 시각 문자열. 형식이 택배사마다 달라 파싱하지 않고 그대로 보여 준다
		 * @param where 어디에서
		 * @param kind  무엇을 했는지 ("집화처리" · "간선상차" 같은 택배사 표현)
		 * @param level 그 시점의 진행 단계
		 */
		public record Step(String time, String where, String kind, int level) {
		}
	}
}
