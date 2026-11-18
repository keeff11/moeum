package store.moeum.moeum.payment.domain;

/**
 * 우리가 FAILED 로 내린 결제에 point3 의 captured 가 도착했다 (D-075).
 *
 * 돈은 빠졌는데 우리 쪽은 실패로 처리해 홀드가 이미 풀렸을 수 있다. 조용히 CAPTURED 로 올리면
 * 풀린 재고를 다시 확정하게 되고, 그대로 두면 구매자는 돈만 나간다. 어느 쪽도 자동으로 고칠 수 없어
 * 상태를 건드리지 않고 사람에게 넘긴다 — 잡는 쪽이 CRITICAL 알림을 보낸다.
 */
public class CapturedAfterFailureException extends RuntimeException {

	private final Long paymentId;
	private final String sessionId;

	public CapturedAfterFailureException(Long paymentId, String sessionId) {
		super("실패 처리된 결제에 승인 성공이 도착했다 (id=" + paymentId + ")");
		this.paymentId = paymentId;
		this.sessionId = sessionId;
	}

	public Long paymentId() {
		return paymentId;
	}

	public String sessionId() {
		return sessionId;
	}
}
