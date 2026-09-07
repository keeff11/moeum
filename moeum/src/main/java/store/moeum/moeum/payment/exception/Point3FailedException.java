package store.moeum.moeum.payment.exception;

/**
 * <b>확정 실패.</b> 4xx 응답이다 — point3 가 요청을 받아들이지 않았다.
 *
 * 승인이 일어났을 가능성이 없으므로 <b>되돌려도 안전하다.</b>
 * 결제를 실패 처리하고 재고 홀드를 풀어도 된다.
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
}
