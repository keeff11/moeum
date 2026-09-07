package store.moeum.moeum.payment.infra;

/**
 * 취소 요청 (point3-api 8절).
 *
 * <b>네 필드 모두 필수이고 세금을 자동 계산하지 않는다.</b> 결제 세션 생성과 정반대다 —
 * 거기서는 vat 를 비워 point3 가 계산하게 했지만, 여기서는 우리가 계산해 보내야 한다.
 *
 * 불변식: {@code refundTaxFreeAmount + refundVat <= refundAmount}
 */
public record Point3RefundRequest(
		int refundAmount,
		int refundTaxFreeAmount,
		int refundVat,
		String reason
) {

	/** reason 은 200자 제한이다. 사유가 길다고 취소가 실패하면 안 된다 */
	public Point3RefundRequest {
		if (reason != null && reason.length() > 200) {
			reason = reason.substring(0, 200);
		}
	}
}
