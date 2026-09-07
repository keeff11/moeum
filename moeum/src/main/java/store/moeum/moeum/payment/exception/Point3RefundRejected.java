package store.moeum.moeum.payment.exception;

/**
 * 취소가 명시적으로 거절됐거나 연동이 잘못됐다 — 422 · 400 · 401 · 404.
 *
 * <b>취소가 일어나지 않았다.</b> 되돌려도 안전하고, 같은 요청을 다시 보내도 같은 결과다.
 *
 * 422 는 point3 가 요청을 이해했지만 거절한 것이고(금액 초과 등),
 * 400·401·404 는 우리 쪽 연동 오류다. 사용자에게 보이는 문구는 달라야 하지만
 * "취소가 안 일어났다" 는 점은 같아 한 예외로 묶는다.
 */
public class Point3RefundRejected extends Point3Exception {

	private final int status;

	public Point3RefundRejected(int status, String message) {
		super(message, null);
		this.status = status;
	}

	public int status() {
		return status;
	}

	/** 우리 쪽 연동 오류인가. 사용자 안내가 아니라 알림이 필요한 종류다 */
	public boolean isIntegrationError() {
		return status == 400 || status == 401 || status == 404;
	}
}
