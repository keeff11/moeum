package store.moeum.moeum.payment.domain;

import store.moeum.moeum.payment.refund.Refund;
import store.moeum.moeum.payment.refund.RefundStatus;

import java.util.Collection;

/**
 * 셀러 결제 내역(G10)의 칩이자 배지 (D-059).
 *
 * <b>칩과 배지를 한 enum 으로 둔다.</b> 화면에서 칩을 누르면 그 배지가 붙은 줄만 남는
 * 관계라, 둘을 따로 만들면 "칩에는 있는데 어느 줄에도 안 붙는 값" 이 생긴다.
 * 필터 파라미터가 비어 있으면 '전체' 다 — {@code ALL} 을 값으로 두지 않은 이유다.
 *
 * <b>판정 순서는 {@link SellerPaymentSql#STATUS_CASE} 와 같아야 한다.</b>
 * 목록·칩 숫자는 SQL 이, 줄에 붙는 배지는 {@link #of} 가 정한다 — 갈라지면
 * '취소 완료' 칩을 눌렀는데 '결제 완료' 배지가 달린 줄이 나온다. 테스트가 둘을 대조한다.
 */
public enum SellerPaymentStatus {

	/** 결제 완료. 아직 아무 취소도 걸리지 않았다 */
	PAID("결제 완료"),

	/**
	 * 정산 완료 — <b>시스템으로는 되돌릴 수 없어 셀러가 직접 이체해야 하는 건</b>이다.
	 *
	 * point3 가 취소를 {@code SETTLEMENT_DEADLINE_EXCEEDED} 로 거절했을 때만 알 수 있다.
	 * 정산 피드가 없어 "정산됐지만 아무도 취소를 시도하지 않은" 결제는 여기 잡히지 않는다.
	 */
	SETTLED("정산 완료"),

	/** 취소 처리중. <b>실패가 아니다</b> — 결과를 모르는 것이고 대사 배치가 끝낸다 */
	CANCELING("취소 처리중"),

	/** 취소 완료. 정산 후 직접 이체를 마친 건도 여기로 내려온다 */
	CANCELED("취소 완료"),

	/** 처리 실패. 결제가 실패했거나 취소가 확정 거절됐다 */
	FAILED("처리 실패"),

	/**
	 * 승인 결과를 기다리는 중. 와이어프레임에 칩이 없어 '전체' 에서만 보인다.
	 *
	 * <b>실패로 접지 않는다</b> (CLAUDE.md 규칙 3). 결과를 모르는 건을 실패라고 부르면
	 * 셀러가 안 들어온 돈으로 알고 움직인다.
	 */
	PENDING("확인 중");

	private final String label;

	SellerPaymentStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** 직접 이체가 남아 있는 건인가. S14 로 들어가는 줄이다 */
	public boolean needsManualRefund() {
		return this == SETTLED;
	}

	/**
	 * 줄에 붙일 배지를 정한다. {@code refunds} 는 <b>이 결제의</b> 취소 행 전부다.
	 *
	 * 순서가 {@link SellerPaymentSql#STATUS_CASE} 와 한 칸도 어긋나면 안 된다.
	 */
	public static SellerPaymentStatus of(Payment payment, Collection<Refund> refunds) {
		if (hasStatus(refunds, RefundStatus.PROCESSING)) {
			return CANCELING;
		}
		if (refunds.stream().anyMatch(Refund::isManualPending)) {
			return SETTLED;
		}
		if (hasStatus(refunds, RefundStatus.COMPLETED)) {
			return CANCELED;
		}
		boolean refundRejected = refunds.stream().anyMatch(
				r -> r.getStatus() == RefundStatus.FAILED && !r.isSettledManual());
		if (payment.getStatus() == PaymentStatus.FAILED || refundRejected) {
			return FAILED;
		}
		return payment.getStatus() == PaymentStatus.CAPTURED ? PAID : PENDING;
	}

	private static boolean hasStatus(Collection<Refund> refunds, RefundStatus status) {
		return refunds.stream().anyMatch(r -> r.getStatus() == status);
	}
}
