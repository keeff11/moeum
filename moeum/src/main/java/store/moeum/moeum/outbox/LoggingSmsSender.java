package store.moeum.moeum.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 문자를 보내지 않고 로그만 남긴다. 로컬에서 인증 흐름을 끝까지 따라가려고 둔다 —
 * 개발 PC 는 SOLAPI IP 허용 목록에 없어 실제로 보낼 수도 없다 (D-040).
 *
 * <b>본문(인증번호)을 로그에 남긴다.</b> 그래야 로컬에서 인증을 마칠 수 있다.
 * 운영에서 이 발송기가 잡히면(provider 가 log) 인증번호가 로그에 남고 구매자는 문자를
 * 받지 못한다 — 그래서 WARN 으로 찍는다. 운영은 반드시 solapi 로 둔다.
 * 번호는 뒤 네 자리만 남긴다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "moeum.notify.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

	@Override
	public void send(String to, String text) {
		String tail = (to.length() >= 4) ? to.substring(to.length() - 4) : "****";
		log.warn("[문자/로그] 실제로 보내지 않았다: to=***{} text={}", tail, text);
	}
}
