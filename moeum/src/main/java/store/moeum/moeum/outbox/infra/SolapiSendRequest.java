package store.moeum.moeum.outbox.infra;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /messages/v4/send-many/detail} 요청 본문.
 *
 * @param allowDuplicates 같은 번호로 같은 내용이 중복 접수되는 것을 SOLAPI 쪽에서 막는다.
 *                        <b>false 로 둔다</b> — 릴레이 재시도가 실제로 두 번 나가는 것을
 *                        한 겹 더 막아 준다. 우리 쪽 멱등 가드(outbox 상태 전이)와 이중 방어다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SolapiSendRequest(List<Message> messages, boolean allowDuplicates) {

	public SolapiSendRequest(List<Message> messages) {
		this(messages, false);
	}

	/**
	 * 한 통.
	 *
	 * @param to   수신번호. 하이픈 없이 숫자만 보낸다
	 * @param from 발신번호. 알림톡이 막혀 문자로 대체될 때, 그리고 문자를 보낼 때 쓰인다
	 * @param text 문자 본문. 알림톡에서는 비운다 — 템플릿이 본문이다
	 * @param type 메시지 유형. 문자는 {@code SMS} 로 못 박는다 — 비우면 SOLAPI 가 길이를 보고
	 *             장문(LMS)으로 바꿔 단가가 오른다. 알림톡에서는 비운다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Message(String to, String from, KakaoOption kakaoOptions, String text, String type) {

		/** 알림톡 */
		public Message(String to, String from, KakaoOption kakaoOptions) {
			this(to, from, kakaoOptions, null, null);
		}

		/** 단문 문자. 90바이트(한글 45자)를 넘기지 않는다 */
		public static Message sms(String to, String from, String text) {
			return new Message(to, from, null, text, "SMS");
		}
	}

	/**
	 * 알림톡 파라미터.
	 *
	 * @param variables  템플릿 변수. 키는 {@code #{userName}} 형태 그대로 넣는다
	 * @param disableSms 대체 발송(문자) 차단 여부. <b>false 로 둔다</b> — 카카오톡을
	 *                   안 쓰는 구매자에게도 2차금 청구는 닿아야 한다. 대신 문자 단가가 붙는다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record KakaoOption(String pfId, String templateId, Map<String, String> variables,
	                          boolean disableSms) {

		public KakaoOption(String pfId, String templateId, Map<String, String> variables) {
			this(pfId, templateId, variables, false);
		}
	}
}
