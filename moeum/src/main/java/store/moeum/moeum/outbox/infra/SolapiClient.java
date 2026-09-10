package store.moeum.moeum.outbox.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * SOLAPI 알림톡 발송.
 *
 * <b>공식 SDK 를 쓰지 않는다.</b> 이유가 셋이다 —
 * ① SDK 는 코틀린 + kotlinx.serialization 이라 Java 17 프로젝트에 런타임을 통째로 들인다,
 * ② 예외를 자기 방식으로 감싸서 <b>4xx 와 5xx 를 나누기가</b> 어렵다 (CLAUDE.md 규칙 2),
 * ③ base URL 이 고정이라 WireMock 으로 실패를 재현하기 어렵다.
 * 실제로 필요한 것은 HMAC 헤더 한 줄과 POST 하나뿐이라 {@code Point3Client} 와 같은 모양으로 붙였다.
 *
 * <pre>
 *   4xx            → SolapiFailedException     확정 실패. 재시도해도 같다
 *   5xx · 타임아웃  → SolapiUncertainException  결과 불명. 실제로 나갔을 수 있다
 * </pre>
 *
 * <b>200 이 성공이 아니다.</b> 응답의 {@code failedMessageList} 에 건별 실패가 담겨 온다.
 * HTTP 상태만 보면 한 건도 안 나갔는데 성공으로 치고 SENT 로 넘긴다 — 알림이 곧 결제
 * 요청이라(2차금) 유실 비용이 크다. point3 에서 "브라우저 성공 신호는 성공이 아니다" 와 같은 판단이다.
 *
 * <b>호출은 트랜잭션 밖에서 한다</b> (CLAUDE.md 규칙 1). 이 클래스는 DB 를 건드리지 않는다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SolapiProperties.class)
public class SolapiClient {

	private static final String SEND_PATH = "/messages/v4/send-many/detail";
	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final SolapiProperties properties;
	private final RestClient restClient;

	public SolapiClient(SolapiProperties properties) {
		this.properties = properties;

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());

		this.restClient = RestClient.builder()
				.baseUrl(properties.baseUrl())
				.requestFactory(factory)
				.build();
	}

	/**
	 * 한 건 발송.
	 *
	 * 단건인데 {@code send-many/detail} 을 쓰는 이유는 <b>건별 실패를 돌려주는 유일한
	 * 엔드포인트</b>라서다. 단건 API 는 실패를 예외로만 알려 줘서 어느 건이 왜 실패했는지
	 * 로그에 남기기 어렵다.
	 */
	public void send(SolapiSendRequest.Message message) {
		if (!properties.hasCredentials()) {
			throw new SolapiFailedException("SOLAPI 설정이 비어 있다 — apiKey · apiSecret · pfId · from 을 확인한다");
		}
		SolapiSendRequest request = new SolapiSendRequest(List.of(message));
		SolapiSendResponse response = post(request);

		requireAllAccepted(response);
	}

	// ---------------------------------------------------------------- 내부

	private SolapiSendResponse post(SolapiSendRequest request) {
		try {
			return restClient.post()
					.uri(SEND_PATH)
					.header(HttpHeaders.AUTHORIZATION, authorization())
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
						throw new SolapiFailedException("SOLAPI 4xx: " + res.getStatusCode());
					})
					.onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
						throw new SolapiUncertainException("SOLAPI 5xx: " + res.getStatusCode());
					})
					.body(SolapiSendResponse.class);

		} catch (SolapiException e) {
			throw e;
		} catch (RestClientException e) {
			// 타임아웃 · 네트워크 오류. 실제로 나갔는지 알 수 없다
			throw new SolapiUncertainException("SOLAPI 호출 실패", e);
		}
	}

	/**
	 * 건별 실패를 예외로 올린다.
	 *
	 * <b>실패 사유로 4xx / 5xx 를 가르지 않는다 — 전부 재시도 대상으로 둔다.</b>
	 * SOLAPI 의 상태 코드는 수십 종이고 그중 무엇이 영구 실패인지 문서만으로는 단정할 수
	 * 없다. 잘못 영구 실패로 판정하면 2차금 청구가 조용히 사라진다. 8회 재시도 뒤
	 * DEAD 로 남기면 사람이 보고 판단할 수 있다 (D-027).
	 */
	private static void requireAllAccepted(SolapiSendResponse response) {
		if (response == null) {
			throw new SolapiUncertainException("SOLAPI 응답이 비어 있다");
		}
		List<SolapiSendResponse.FailedMessage> failed = response.failedMessageList();

		if (failed != null && !failed.isEmpty()) {
			SolapiSendResponse.FailedMessage first = failed.get(0);
			throw new SolapiUncertainException(
					"SOLAPI 발송 거절: statusCode=" + first.statusCode() + " " + first.statusMessage());
		}
	}

	/**
	 * 인증 헤더.
	 *
	 * <pre>
	 *   HMAC-SHA256 Apikey={키}, Date={ISO-8601}, salt={UUID}, signature={hex(HMAC(secret, date+salt))}
	 * </pre>
	 *
	 * salt 와 date 를 매번 새로 만든다 — 같은 서명을 재사용하면 재전송 공격을 막지 못한다.
	 * <b>apiSecret 은 서명에만 쓰이고 요청에 실리지 않는다.</b>
	 */
	private String authorization() {
		String date = Instant.now().toString();
		String salt = UUID.randomUUID().toString().replace("-", "");

		return "HMAC-SHA256 Apikey=" + properties.apiKey()
				+ ", Date=" + date
				+ ", salt=" + salt
				+ ", signature=" + sign(date + salt);
	}

	private String sign(String payload) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(properties.apiSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));

			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));

		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			// 알고리즘은 JDK 표준이고 키는 설정값이다. 여기 오면 설정이 잘못된 것이다
			throw new SolapiFailedException("SOLAPI 서명 생성 실패", e);
		}
	}
}
