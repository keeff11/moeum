package store.moeum.moeum.payment.exception;

import store.moeum.moeum.payment.infra.RefundConflictCode;

/**
 * 취소 409. <b>코드를 봐야 처리가 갈린다</b> (point3-api 8절).
 *
 * {@link RefundConflictCode#isConfirmedRejection()} 이 true 면 취소가 일어나지 않았으므로
 * 실패로 확정해도 안전하고, false 면 <b>새 취소를 만들지 말고</b> 조회 → resume 으로 확인해야 한다.
 * 여기서 새 Idempotency-Key 로 재요청하면 같은 취소가 두 번 실행된다.
 */
public class Point3RefundConflict extends Point3Exception {

	private final RefundConflictCode code;

	public Point3RefundConflict(RefundConflictCode code) {
		super("point3 취소 충돌: " + code, null);
		this.code = code;
	}

	public RefundConflictCode code() {
		return code;
	}
}
