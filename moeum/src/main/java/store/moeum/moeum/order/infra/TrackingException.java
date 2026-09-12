package store.moeum.moeum.order.infra;

/**
 * 배송조회 실패.
 *
 * <b>4xx 와 5xx 를 나누지 않는다.</b> point3 · SOLAPI 는 나눠야 했다 — 돈이 움직이는
 * 호출이라 "확정 실패(되돌려도 안전)" 와 "결과 불명(되돌리면 안 됨)" 의 처리가 정반대였다
 * (CLAUDE.md 규칙 2).
 *
 * 배송조회는 <b>읽기</b>다. 실패하면 어느 쪽이든 화면에 조회 결과를 못 보여 주는 것으로
 * 끝나고, 되돌릴 것도 잃을 돈도 없다. 여기서 쪼개면 쓰지 않는 분기만 생긴다.
 * 원인은 로그로 남긴다.
 */
public class TrackingException extends RuntimeException {

	public TrackingException(String message) {
		super(message);
	}

	public TrackingException(String message, Throwable cause) {
		super(message, cause);
	}
}
