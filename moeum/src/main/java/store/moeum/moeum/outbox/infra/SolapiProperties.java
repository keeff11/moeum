package store.moeum.moeum.outbox.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import store.moeum.moeum.outbox.domain.OutboxEventType;

import java.util.Map;

/**
 * SOLAPI 알림톡 설정.
 *
 * <b>키는 코드에도 저장소에도 두지 않는다.</b> 운영에서는 Parameter Store 의
 * SecureString 을 환경변수로 주입한다 (D-017). {@code apiSecret} 은 서명에만 쓰이고
 * 요청에 실려 나가지 않는다.
 *
 * @param templates 이벤트별 승인 템플릿 id. <b>없는 이벤트는 발송하지 않고 로그만 남긴다</b> —
 *                  승인된 템플릿만 보낼 수 있어서, 미승인 건을 보내면 4xx 로 떨어지고
 *                  8회 재시도 끝에 DEAD 로 쌓인다
 * @param from      발신번호. 알림톡이 실패해 문자로 대체 발송될 때 쓰인다.
 *                  SOLAPI 에 사전 등록된 번호여야 한다
 * @param linkBase  버튼 링크의 앞부분. 템플릿의 {@code #{LINK}} 에 채운다
 * @param testRecipient 테스트 수신번호. <b>값이 있으면 모든 알림이 이 번호로만 간다</b> —
 *                      구매자에게는 한 통도 가지 않는다. 실서비스에서는 반드시 비운다
 */
@ConfigurationProperties(prefix = "moeum.notify.solapi")
public record SolapiProperties(
		String baseUrl,
		String apiKey,
		String apiSecret,
		String pfId,
		String from,
		String linkBase,
		String testRecipient,
		Map<OutboxEventType, String> templates,
		int connectTimeout,
		int readTimeout
) {

	public SolapiProperties {
		baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.solapi.com" : baseUrl;
		templates = (templates == null) ? Map.of() : templates;
		connectTimeout = (connectTimeout <= 0) ? 3000 : connectTimeout;
		readTimeout = (readTimeout <= 0) ? 10000 : readTimeout;
	}

	/**
	 * 이 이벤트를 보낼 수 있는가. 템플릿 id 가 있어야 보낸다.
	 *
	 * <b>빈 문자열은 없는 것으로 친다.</b> 설정이 {@code ORDER_PAID: ${...:}} 형태라
	 * 환경변수가 없으면 null 이 아니라 "" 가 들어온다 — 그대로 보내면 템플릿 id 가 빈 채
	 * 요청이 나가고 4xx 를 받는다.
	 */
	public String templateOf(OutboxEventType eventType) {
		String templateId = templates.get(eventType);
		return notBlank(templateId) ? templateId : null;
	}

	/**
	 * 테스트 수신번호가 걸려 있는가.
	 *
	 * <b>걸려 있으면 구매자에게 가지 않는다.</b> 실서비스로 넘어갈 때 이 파라미터를
	 * 지우는 것을 잊으면 모든 알림이 한 사람에게만 간다 — 잊기 쉬운 자리라
	 * 발송할 때마다 WARN 을 남긴다.
	 */
	public String testRecipientOrNull() {
		return notBlank(testRecipient) ? digitsOf(testRecipient) : null;
	}

	private static String digitsOf(String phone) {
		return phone.replaceAll("[^0-9]", "");
	}

	public boolean hasCredentials() {
		return notBlank(apiKey) && notBlank(apiSecret) && notBlank(pfId) && notBlank(from);
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
