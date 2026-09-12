package store.moeum.moeum.order.infra;

import java.util.Optional;

/**
 * 배송조회 실패.
 *
 * <b>4xx 와 5xx 를 나누지 않는다.</b> point3 · SOLAPI 는 나눠야 했다 — 돈이 움직이는
 * 호출이라 "확정 실패(되돌려도 안전)" 와 "결과 불명(되돌리면 안 됨)" 의 처리가 정반대였다
 * (CLAUDE.md 규칙 2).
 *
 * 배송조회는 <b>읽기</b>다. 실패하면 어느 쪽이든 화면에 조회 결과를 못 보여 주는 것으로
 * 끝나고, 되돌릴 것도 잃을 돈도 없다. 여기서 쪼개면 쓰지 않는 분기만 생긴다.
 *
 * <b>대신 "이 사유를 구매자에게 보여도 되는가" 로 나눈다.</b> 택배사가 준 사유
 * ("운송장 번호가 올바르지 않습니다")는 그대로 보여 주는 것이 도움이 되지만,
 * 우리 쪽 사정("응답이 비어 있다", "키가 설정되지 않았다")은 구매자가 읽을 문구가 아니다.
 * 나누지 않으면 내부 문구가 그대로 응답에 실린다.
 */
public class TrackingException extends RuntimeException {

	/** 화면에 그대로 보여도 되는 문구. null 이면 일반 문구로 바꿔 보여 준다 */
	private final String userMessage;

	private TrackingException(String message, String userMessage, Throwable cause) {
		super(message, cause);
		this.userMessage = userMessage;
	}

	/** 택배사가 준 사유. 구매자가 읽고 조치할 수 있는 문구다 */
	public static TrackingException ofProvider(String providerMessage) {
		return new TrackingException("택배사가 조회를 거절했다: " + providerMessage, providerMessage, null);
	}

	/** 우리 쪽 사정(네트워크 · 형식 · 설정). 사유는 로그에만 남는다 */
	public static TrackingException internal(String message) {
		return new TrackingException(message, null, null);
	}

	public static TrackingException internal(String message, Throwable cause) {
		return new TrackingException(message, null, cause);
	}

	public Optional<String> userMessage() {
		return Optional.ofNullable(userMessage);
	}
}
