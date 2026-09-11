package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 진행 중인 결제 목록 (D-042).
 *
 * <b>구매자가 {@code orderToken} 을 잃어버렸을 때 되찾는 경로다.</b> 토큰은 {@code /pay}
 * 응답으로만 내려가서, 새로고침하거나 브라우저를 닫으면 결제 중인 주문을 가리킬 수단이
 * 사라진다. 그러면 폴링도 confirm 도 못 하고, 구매자는 돈이 나갔는지도 모르는 채 남는다.
 *
 * <b>목록이 비어 있는 것이 정상이다.</b> 결제를 끝냈거나 애초에 시작하지 않은 구매자가
 * 대부분이다 — 비었다고 오류가 아니다.
 */
@Schema(description = "진행 중인 결제 목록")
public record InProgressOrderResponse(

		@Schema(description = "진행 중인 결제. 최근 것이 위에 온다. 없으면 빈 배열이다")
		List<InProgressOrder> items
) {

	@Schema(description = "진행 중인 결제 한 건")
	public record InProgressOrder(

			@Schema(description = "이 주문을 가리키는 토큰. 상태 조회 · 승인에 쓰는 값이다",
					example = "ord_01K4Z8Q2N7V9")
			String orderToken,

			@Schema(description = "차수. FIRST=1차금, SECOND=2차금")
			PaymentPhase phase,

			@Schema(description = "왜 아직 진행 중인가. <b>둘의 대응이 정반대다</b> — "
					+ "AWAITING_PAYMENT 는 결제를 이어서 진행해야 하고, CONFIRMING 은 기다려야 한다")
			PaymentResultResponse.PendingReason pendingReason,

			@Schema(description = "이 차수에 청구된 금액", example = "20000")
			int amount,

			@Schema(description = "무엇을 사는 중인지. 폼이 여럿이면 '외 N건' 이 붙는다",
					example = "아크릴 스탠드 — 2차 공구")
			String title,

			@Schema(description = "이 결제를 시작한 시각", example = "2026-09-11T14:02:11")
			LocalDateTime startedAt
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static InProgressOrderResponse of(List<Payment> payments) {
		return new InProgressOrderResponse(payments.stream().map(InProgressOrderResponse::itemOf).toList());
	}

	private static InProgressOrder itemOf(Payment payment) {
		OrderGroup group = payment.getOrderGroup();

		return new InProgressOrder(
				group.getOrderToken(),
				payment.getPhase(),
				reasonOf(payment.getStatus()),
				payment.getAmount(),
				group.representativeTitle(),
				payment.getCreatedAt());
	}

	/**
	 * 쿼리가 두 상태만 골라 오므로 다른 값은 올 수 없다.
	 *
	 * 그래도 {@code default} 를 두지 않고 남은 셋을 명시한다 — 상태가 늘었을 때
	 * 컴파일이 깨져야 여기를 같이 보게 된다.
	 */
	private static PaymentResultResponse.PendingReason reasonOf(PaymentStatus status) {
		return switch (status) {
			case CREATED -> PaymentResultResponse.PendingReason.AWAITING_PAYMENT;
			case CAPTURE_PENDING -> PaymentResultResponse.PendingReason.CONFIRMING;
			case CAPTURED, FAILED -> throw new IllegalStateException(
					"진행 중이 아닌 결제가 목록에 섞였다: " + status);
		};
	}
}
