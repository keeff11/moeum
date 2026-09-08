package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 결제 결과. confirm 응답이자 상태 조회 응답이다.
 *
 * <b>PENDING 은 실패가 아니다.</b> 승인 결과를 아직 모르는 상태이고, 프론트는 여기서
 * "확인 중" 화면을 띄우고 상태 조회를 반복해야 한다. 실패로 안내하면 사용자가
 * 다시 결제해 이중 결제가 된다.
 */
public record PaymentResultResponse(
		@Schema(description = "이 주문을 가리키는 토큰")
		String orderToken,

		@Schema(description = "결제 결과. PENDING 은 실패가 아니다 — 다시 결제시키면 이중 결제가 된다")
		Status status,

		@Schema(description = "사용자에게 그대로 보여도 되는 안내 문구")
		String message
) {

	@Schema(description = """
			PAID=결제 완료 · PENDING=결과 확인 중(다시 결제시키지 말고 상태 조회를 반복한다) · FAILED=실패""")
	public enum Status {
		/** 출금 완료. 이것만이 성공이다 */
		PAID,
		/** 결과 확인 중. 대사 배치가 확정한다 — 다시 결제하게 하면 안 된다 */
		PENDING,
		/** 확정 실패. 홀드가 풀렸고 다시 시도할 수 있다 */
		FAILED
	}

	public static PaymentResultResponse paid(String orderToken) {
		return new PaymentResultResponse(orderToken, Status.PAID, "결제가 완료되었습니다.");
	}

	public static PaymentResultResponse pending(String orderToken) {
		return new PaymentResultResponse(orderToken, Status.PENDING,
				"결제 결과를 확인하고 있습니다. 잠시만 기다려 주세요.");
	}

	public static PaymentResultResponse failed(String orderToken, String message) {
		return new PaymentResultResponse(orderToken, Status.FAILED, message);
	}
}
