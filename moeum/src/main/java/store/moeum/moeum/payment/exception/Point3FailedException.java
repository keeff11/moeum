package store.moeum.moeum.payment.exception;

/**
 * <b>확정 실패.</b> 4xx 응답이다 — point3 가 요청을 받아들이지 않았다.
 *
 * 승인이 일어났을 가능성이 없으므로 <b>되돌려도 안전하다.</b>
 * 결제를 실패 처리하고 재고 홀드를 풀어도 된다.
 *
 * <b>단 401 · 403 은 되돌리지 않는다</b> (D-082). 돈은 안전하지만 원인이 구매자가 아니라
 * 우리 토큰 · 설정이다. 승인 마감 전에 고치면 같은 승인을 다시 불러 살릴 수 있다.
 *
 * @param status HTTP 상태 코드. 400 은 요청 형식, 401·403 은 자격증명, 404 는 없는 세션이다
 */
public class Point3FailedException extends Point3Exception {

	private final int status;

	public Point3FailedException(int status, String message) {
		super(message, null);
		this.status = status;
	}

	public int status() {
		return status;
	}

	/**
	 * point3 가 우리 자격증명을 거부했다 (401 · 403). 결제 건의 문제가 아니라 시스템 장애다.
	 *
	 * 0(토큰 미설정)은 넣지 않는다 — 토큰 발급 전에 확정 실패로 다루려고 만든 값이다.
	 */
	public boolean isCredentialRejected() {
		return status == 401 || status == 403;
	}
}
