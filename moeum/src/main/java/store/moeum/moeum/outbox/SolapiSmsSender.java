package store.moeum.moeum.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import store.moeum.moeum.outbox.infra.SolapiClient;

/** SOLAPI 단문 문자. {@code moeum.notify.provider=solapi} 일 때 뜬다 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "moeum.notify.provider", havingValue = "solapi")
public class SolapiSmsSender implements SmsSender {

	private final SolapiClient solapiClient;

	@Override
	public void send(String to, String text) {
		solapiClient.sendSms(to, text);
	}
}
