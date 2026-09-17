package store.moeum.moeum.outbox;

/**
 * 단문 문자 발송 (D-064 인증번호).
 *
 * 알림톡과 같은 스위치({@code moeum.notify.provider})로 갈아 끼운다. 자격증명이 같은 SOLAPI
 * 계정이라 따로 두면 하나만 켜고 하나를 잊는다.
 *
 * 실패는 {@link store.moeum.moeum.outbox.infra.SolapiFailedException}(확정) 과
 * {@link store.moeum.moeum.outbox.infra.SolapiUncertainException}(불명) 으로 올린다.
 * <b>트랜잭션 밖에서 부른다</b> (CLAUDE.md 규칙 1).
 */
public interface SmsSender {

	/**
	 * @param to   숫자만 남긴 수신번호
	 * @param text 90바이트(한글 45자) 이하 본문
	 */
	void send(String to, String text);
}
