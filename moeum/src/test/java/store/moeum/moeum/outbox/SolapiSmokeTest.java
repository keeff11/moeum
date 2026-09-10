package store.moeum.moeum.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.outbox.infra.SolapiClient;
import store.moeum.moeum.outbox.infra.SolapiProperties;
import store.moeum.moeum.outbox.infra.SolapiSendRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 실제 SOLAPI 로 알림톡 한 통을 보낸다. <b>돈이 나가고 사람에게 도착한다.</b>
 *
 * 그래서 평소에는 돌지 않는다 — {@code SOLAPI_SMOKE_TO} 가 있을 때만 켜진다.
 * WireMock 테스트가 검증하지 못하는 것을 확인하려고 남겨 둔다.
 *
 * <ul>
 *   <li>HMAC 서명이 <b>실제로 통하는지</b> — 계산은 맞아도 형식이 틀리면 401 이다</li>
 *   <li>pfId · templateId 가 그 계정의 것이 맞는지</li>
 *   <li><b>템플릿 변수 이름이 승인본과 일치하는지</b> — 하나라도 어긋나면 거절된다.
 *       이건 승인된 템플릿을 가진 실제 계정으로만 확인할 수 있다</li>
 * </ul>
 *
 * 돌리는 법 (PowerShell):
 * <pre>
 *   $env:SOLAPI_SMOKE_TO="01012341234"
 *   ./gradlew.bat test --tests '*SolapiSmokeTest*'
 * </pre>
 */
class SolapiSmokeTest {

	private static final DateTimeFormatter BILL_TIME =
			DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

	@Test
	@DisplayName("실제로_알림톡_한_통을_보낸다")
	@EnabledIfEnvironmentVariable(named = "SOLAPI_SMOKE_TO", matches = ".+")
	void 실발송() {
		SolapiProperties properties = new SolapiProperties(
				null,
				System.getenv("SOLAPI_API_KEY"),
				System.getenv("SOLAPI_API_SECRET"),
				System.getenv("SOLAPI_PF_ID"),
				System.getenv("SOLAPI_FROM"),
				"https://www.moeum.store",
				Map.of(OutboxEventType.ORDER_PAID, System.getenv("SOLAPI_TEMPLATE_ORDER_PAID")),
				3000, 10000);

		Map<String, String> variables = new LinkedHashMap<>();
		variables.put("#{userName}", "테스터");
		variables.put("#{goodsName}", "아크릴 스탠드 — 2차 공구");
		variables.put("#{prepayment}", "20000");
		variables.put("#{billTime}", LocalDateTime.now().format(BILL_TIME));
		variables.put("#{LINK}", "https://www.moeum.store/orders/ord_smoke_test");

		SolapiSendRequest.Message message = new SolapiSendRequest.Message(
				System.getenv("SOLAPI_SMOKE_TO"),
				properties.from(),
				new SolapiSendRequest.KakaoOption(properties.pfId(),
						properties.templateOf(OutboxEventType.ORDER_PAID), variables));

		assertThatCode(() -> new SolapiClient(properties).send(message))
				.doesNotThrowAnyException();
	}
}
