package store.moeum.moeum.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.SellerPaymentCounts;
import store.moeum.moeum.payment.domain.SellerPaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 셀러 결제 내역 (와이어프레임 G10) — 칩 숫자 + 줄 + 페이지 (D-059).
 *
 * <b>한 줄이 결제 한 건이다.</b> 주문묶음 × 차수 — 같은 주문의 1차금과 2차금이 두 줄로
 * 나온다. 셀러 주문 목록(G6)이 묶음 하나를 한 줄로 접는 것과 일부러 다르다 (D-033):
 * 거기서 셀러가 보는 것은 "이 주문을 어떻게 처리할까" 이고, 여기서 보는 것은
 * "언제 얼마가 들어왔고 그중 무엇이 되돌아갔는가" 다. 차수가 곧 돈의 단위다.
 */
@Schema(description = "셀러 결제 내역")
public record SellerPaymentPageResponse(

		@Schema(description = "칩 옆 숫자")
		ChipCounts counts,

		@Schema(description = "결제 줄. 결제한 시각 기준 최신순이다")
		List<SellerPaymentItem> items,

		@Schema(description = "페이지 정보")
		PageInfo page
) {

	/**
	 * 칩 옆 숫자. 칩을 바꿔도 이 값은 그대로다 — 칩 조건만 빼고 검색어·판매별 필터는 걸린 값이다.
	 */
	@Schema(description = "칩별 건수")
	public record ChipCounts(

			@Schema(description = "전체. 결제창까지 가지 않은 세션과 만료된 묶음은 세지 않는다",
					example = "10")
			long all,

			@Schema(description = "결제 완료 — 아직 아무 취소도 걸리지 않은 건", example = "6")
			long paid,

			@Schema(description = "정산 완료 — 시스템으로 되돌릴 수 없어 직접 이체해야 하는 건. "
					+ "이 숫자가 곧 S14 환불 처리의 할 일 수다", example = "2")
			long settled,

			@Schema(description = "취소 처리중. 결과를 모르는 건이라 다시 누르게 하면 안 된다",
					example = "0")
			long canceling,

			@Schema(description = "취소 완료. 직접 이체를 마친 건도 여기로 내려온다", example = "1")
			long canceled,

			@Schema(description = "처리 실패 — 결제가 실패했거나 취소가 확정 거절됐다", example = "1")
			long failed,

			@Schema(description = "확인 중. <b>칩이 없다</b> — 전체에만 섞여 있다. "
					+ "그래서 칩 숫자의 합이 all 보다 작을 수 있고, 그게 정상이다",
					example = "0")
			long pending
	) {
	}

	/** 줄 한 개 */
	@Schema(description = "결제 줄")
	public record SellerPaymentItem(

			@Schema(description = "결제번호. 주문번호 뒤에 차수를 붙인 값이다. "
					+ "상세·취소는 이 값으로 부른다", example = "ORD-260828-092-1")
			String paymentNo,

			@Schema(description = "주문번호. 셀러 주문 목록(G6)의 그 값이다",
					example = "ORD-260828-092")
			String orderNo,

			@Schema(description = "차수. FIRST=1차금, SECOND=2차금(잔금+배송비)", example = "FIRST")
			PaymentPhase phase,

			@Schema(description = "차수의 한글 표기", example = "1차금")
			String phaseLabel,

			@Schema(description = "대표 판매 폼 제목. 폼이 여럿이면 '외 N건' 이 붙는다",
					example = "아크릴 스탠드 — 2차 공구")
			String title,

			@Schema(description = "수령인 이름. 카카오 닉네임이 아니라 배송지에 적힌 이름이다",
					example = "김서연")
			String buyerName,

			@Schema(description = "이 차수의 결제 금액", example = "32000")
			int amount,

			@Schema(description = "줄에 찍히는 배지")
			SellerPaymentStatus status,

			@Schema(description = "배지의 한글 표기", example = "결제 완료")
			String statusLabel,

			@Schema(description = "결제된 시각. 아직 확정되지 않았으면 비어 있다",
					example = "2026-08-18T14:22:10")
			LocalDateTime paidAt,

			@Schema(description = "셀러가 계좌로 직접 이체해야 하는 건인가. "
					+ "true 면 줄을 눌렀을 때 환불 처리(S14) 로 들어간다", example = "false")
			boolean needsManualRefund
	) {
	}

	/**
	 * @param hasNext 프론트가 무한 스크롤에 쓴다. totalPages 로 계산하게 두면
	 *                마지막 페이지 판정을 각자 다르게 하다가 한 번 더 부른다
	 */
	@Schema(description = "페이지 정보")
	public record PageInfo(

			@Schema(description = "현재 페이지 번호. 0부터 시작한다", example = "0")
			int page,

			@Schema(description = "실제 적용된 페이지 크기. 요청값이 50을 넘으면 50으로 줄어든다",
					example = "20")
			int size,

			@Schema(description = "조건에 맞는 전체 결제 건수", example = "37")
			long totalElements,

			@Schema(description = "전체 페이지 수", example = "2")
			int totalPages,

			@Schema(description = "다음 페이지가 있는가. 무한 스크롤은 이 값만 보면 된다",
					example = "true")
			boolean hasNext
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static ChipCounts countsOf(SellerPaymentCounts counts) {
		return new ChipCounts(counts.total(), counts.paid(), counts.settled(),
				counts.canceling(), counts.canceled(), counts.failed(), counts.pending());
	}

	/**
	 * @param shipping 없을 수 있다 — 배송지 스냅샷 이전에 결제된 주문이다.
	 *                 그때는 카카오 닉네임으로 대신한다 (G6 와 같은 규칙)
	 */
	public static SellerPaymentItem itemOf(Payment payment, Shipping shipping,
	                                       SellerPaymentStatus status) {
		OrderGroup group = payment.getOrderGroup();

		return new SellerPaymentItem(
				payment.paymentNo(),
				group.getOrderNo(),
				payment.getPhase(),
				phaseLabel(payment.getPhase()),
				group.representativeTitle(),
				shipping != null ? shipping.getRecipientName() : group.getBuyer().getNickname(),
				payment.getAmount(),
				status,
				status.label(),
				payment.getCapturedAt(),
				status.needsManualRefund());
	}

	public static String phaseLabel(PaymentPhase phase) {
		return phase == PaymentPhase.FIRST ? "1차금" : "2차금";
	}
}
