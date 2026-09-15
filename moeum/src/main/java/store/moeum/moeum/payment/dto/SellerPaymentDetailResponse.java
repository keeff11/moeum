package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.SellerPaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 상세 드로어 (G10 상세) 와 환불 처리 (S14) — <b>한 응답이 두 화면을 그린다</b> (D-059).
 *
 * 두 화면이 보는 것은 같은 결제 한 건이고, 다른 것은 "지금 무엇을 누를 수 있는가" 뿐이다.
 * 따로 열면 드로어에서 본 상태와 환불 처리 화면의 상태가 어긋나는 순간이 생긴다 —
 * 셀러 홈을 요청 하나로 준 것과 같은 판단이다 (D-038 결정 1).
 *
 * <pre>
 *   cancel.cancelable = true   → [결제 취소] 버튼을 켠다
 *   manualRefund != null       → 환불 처리(S14) 칸을 그린다
 *   manualRefund.completed     → [환불 완료로 변경] 버튼을 끈다
 * </pre>
 */
@Schema(description = "셀러 결제 상세")
public record SellerPaymentDetailResponse(

		@Schema(description = "결제번호", example = "ORD-260828-092-1")
		String paymentNo,

		@Schema(description = "주문번호", example = "ORD-260828-092")
		String orderNo,

		@Schema(description = "차수. FIRST=1차금, SECOND=2차금(잔금+배송비)", example = "FIRST")
		PaymentPhase phase,

		@Schema(description = "차수의 한글 표기", example = "1차금")
		String phaseLabel,

		@Schema(description = "판매 — 대표 판매 폼 제목", example = "아크릴 스탠드 — 2차 공구")
		String title,

		@Schema(description = "상품 · 옵션. 주문 시점 스냅샷이라 폼을 고쳐도 바뀌지 않는다")
		List<ItemLine> items,

		@Schema(description = "구매자 — 배송지에 적힌 수령인 이름", example = "김서연")
		String buyerName,

		@Schema(description = "이 차수의 결제 금액", example = "32000")
		int amount,

		@Schema(description = "일시 — 결제된 시각. 아직 확정되지 않았으면 비어 있다",
				example = "2026-08-18T14:22:10")
		LocalDateTime paidAt,

		@Schema(description = """
				수단 — <b>지금은 항상 비어 있다.</b> point3 가 승인 응답에 결제 수단을 주지 않아
				서버에 저장된 값이 없다. 프론트는 비어 있으면 그 줄을 '—' 로 두면 된다""",
				example = "null")
		String method,

		@Schema(description = "상태 배지")
		SellerPaymentStatus status,

		@Schema(description = "배지의 한글 표기", example = "결제 완료")
		String statusLabel,

		@Schema(description = "결제 취소 버튼이 쓸 값")
		CancelSection cancel,

		@Schema(description = "정산 후 직접 환불(S14). 해당 건이 아니면 비어 있다")
		ManualRefundSection manualRefund
) {

	@Schema(description = "상품 · 옵션 한 줄")
	public record ItemLine(

			@Schema(description = "판매 폼 제목", example = "아크릴 스탠드 — 2차 공구")
			String saleFormTitle,

			@Schema(description = "주문 시점의 상품명", example = "아크릴 스탠드")
			String productName,

			@Schema(description = "주문 시점의 옵션명", example = "옵션 A")
			String optionName,

			@Schema(description = "수량", example = "1")
			int qty,

			@Schema(description = "이 폼만 따로 취소됐는가", example = "false")
			boolean canceled
	) {
	}

	/**
	 * 버튼을 눌러 보고 튕기게 두지 않는다 — 켤지 말지와 왜 껐는지를 서버가 정해서 준다.
	 *
	 * <b>구매자 취소와 같은 판정을 쓴다</b> ({@code RefundPolicy}). 셀러라고 발주가 끝난
	 * 공구를 되돌릴 수 있는 것이 아니다 — 그 수량으로 이미 발주가 나갔다.
	 */
	@Schema(description = "결제 취소 가능 여부")
	public record CancelSection(

			@Schema(description = "지금 [결제 취소] 를 켜도 되는가", example = "true")
			boolean cancelable,

			@Schema(description = "끈 이유. 켜져 있으면 비어 있다",
					example = "발주가 시작되어 취소할 수 없습니다. 판매자에게 문의해 주세요.")
			String blockedReason,

			@Schema(description = "PG 정산 시간대(23:30~00:30)라 막힌 경우 풀리는 시각. "
					+ "이때는 '실패'가 아니라 '00:30 이후에 가능'으로 안내한다",
					example = "2026-08-19T00:30:00")
			LocalDateTime blockedUntil,

			@Schema(description = "지금 취소하면 이 주문에서 돌아갈 금액. 1차금·2차금을 합친 값이고 "
					+ "남은 폼을 전부 취소할 때만 배송비가 포함된다", example = "35000")
			int refundableAmount
	) {
	}

	/**
	 * 정산 후 환불 처리 (S14 · D-059).
	 *
	 * <b>여기가 계좌번호가 나가는 유일한 자리다.</b> point3 로 되돌릴 수 없어 셀러가
	 * 직접 이체하는 것 말고 방법이 없는 건이고, 이체하려면 전체 번호가 필요하다.
	 * 그래서 <b>처리가 끝나면 다시 가린다</b> — 끝난 건의 계좌번호가 목록을 훑을 때마다
	 * 계속 내려갈 이유가 없다.
	 */
	@Schema(description = "정산 후 직접 환불")
	public record ManualRefundSection(

			@Schema(description = "환불 신청 — 구매자가 취소를 건 시각", example = "2026-08-26T10:02:11")
			LocalDateTime requestedAt,

			@Schema(description = "돌려줄 금액", example = "32000")
			int amount,

			@Schema(description = "이체를 마쳤다고 표시됐는가", example = "false")
			boolean completed,

			@Schema(description = "완료로 바꾼 시각. 아직이면 비어 있다")
			LocalDateTime completedAt,

			@Schema(description = "상태 문구", example = "환불 대기")
			String statusLabel,

			@Schema(description = "화면에 그대로 띄우는 안내",
					example = "정산 후에는 구매자에게 직접 이체해야 해요")
			String notice,

			@Schema(description = "보낼 계좌. 처리가 끝나면 비어 있다")
			Account account
	) {
	}

	@Schema(description = "구매자 환불 계좌")
	public record Account(

			@Schema(description = "은행", example = "카카오뱅크")
			String bank,

			@Schema(description = "계좌번호 <b>전체</b>. 이체에 필요해서 여는 값이다",
					example = "3333-01-1234567")
			String accountNo,

			@Schema(description = "예금주", example = "김서연")
			String holderName
	) {
	}
}
