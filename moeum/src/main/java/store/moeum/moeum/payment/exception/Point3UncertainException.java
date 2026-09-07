package store.moeum.moeum.payment.exception;

/**
 * <b>결과 불명.</b> 5xx · 타임아웃 · 네트워크 오류다.
 *
 * <b>승인이 이미 일어났을 수 있다. 아무것도 되돌리지 않는다</b> (CLAUDE.md 규칙 3).
 * 결제를 실패로 바꾸지 말고, 홀드도 풀지 말고, {@code CAPTURE_PENDING} 을 유지한 채
 * 대사 배치가 세션을 재조회해 확정할 때까지 기다린다 (D-005).
 *
 * 여기서 홀드를 풀면 남에게 그 재고가 팔리고, 이쪽 구매자는 돈만 나간 상태가 된다.
 */
public class Point3UncertainException extends Point3Exception {

	public Point3UncertainException(String message, Throwable cause) {
		super(message, cause);
	}
}
