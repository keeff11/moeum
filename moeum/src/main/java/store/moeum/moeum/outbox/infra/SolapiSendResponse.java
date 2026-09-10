package store.moeum.moeum.outbox.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 발송 접수 응답.
 *
 * <b>{@code failedMessageList} 가 비어 있어야 접수된 것이다.</b> HTTP 200 이어도
 * 여기에 건이 담겨 오면 그 건은 나가지 않았다.
 *
 * 필요한 것만 받는다 — groupInfo 의 잔액·단가 같은 값은 쓰지 않으므로 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SolapiSendResponse(List<FailedMessage> failedMessageList,
                                 List<SentMessage> messageList) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FailedMessage(String to, String statusCode, String statusMessage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SentMessage(String messageId, String statusCode, String statusMessage) {
	}
}
