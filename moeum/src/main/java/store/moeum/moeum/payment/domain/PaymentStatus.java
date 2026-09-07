package store.moeum.moeum.payment.domain;

/**
 * payment.status — 결제 한 건의 상태.
 *
 * <pre>
 *   CREATED ──▶ CAPTURE_PENDING ──▶ CAPTURED
 *      │                │
 *      └──▶ FAILED ◀────┘
 * </pre>
 *
 * <b>CAPTURE_PENDING 이 이 상태 머신의 핵심이다.</b> 승인 API 를 부르기 <em>전에</em>
 * 커밋해 두는 값이고(D-004), 이 기록이 없으면 서버가 그 사이에 죽었을 때
 * 출금이 됐는지 안 됐는지 알아낼 방법이 사라진다.
 */
public enum PaymentStatus {

	/** 세션은 만들었고 아직 결제창에 들어가지 않았거나 진행 중이다 */
	CREATED,

	/**
	 * 승인을 요청했거나 요청하려는 참이다. <b>결과를 모른다.</b>
	 *
	 * 이 상태에서는 아무것도 되돌리지 않는다 — 홀드를 풀지도, 실패로 바꾸지도 않는다.
	 * 대사 배치가 point3 에 물어 확정할 때까지 그대로 둔다 (D-005).
	 */
	CAPTURE_PENDING,

	/** 출금 완료. 되돌릴 일이 있으면 취소(환불) API 를 거쳐야 한다 */
	CAPTURED,

	/** 확정 실패. 홀드를 풀어도 안전하고, 같은 행을 재사용해 다시 시도할 수 있다 (D-023) */
	FAILED;

	/** 결과를 모르는 상태. 되돌리기 금지 */
	public boolean isPending() {
		return this == CAPTURE_PENDING;
	}

	/**
	 * 재결제로 이 행을 재사용해도 되는가 (D-023).
	 *
	 * FAILED 만 허용한다. CAPTURE_PENDING 을 덮으면 실제로 출금된 건의
	 * 유일한 기록이 사라져 추적이 불가능해진다.
	 */
	public boolean isReusable() {
		return this == FAILED;
	}
}
