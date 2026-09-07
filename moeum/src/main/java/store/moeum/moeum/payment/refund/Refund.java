package store.moeum.moeum.payment.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.global.jpa.BaseTimeEntity;

/**
 * 취소 한 건. point3 의 환불 항목 하나에 대응한다.
 *
 * <b>요청을 보내기 전에 이 행을 먼저 커밋한다.</b> 승인의 {@code CAPTURE_PENDING} 과 같은 이유다 —
 * 여기에 {@code idempotencyKey} 를 남겨야 타임아웃 났을 때 무엇으로 보냈는지 알고 조회할 수 있다.
 *
 * point3 는 payment 연관을 모르지만 우리는 알아야 한다.
 * 1차금·2차금이 서로 다른 세션이라, 한 폼을 취소하면 이 행이 차수마다 하나씩 생긴다.
 */
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	/** 특정 폼만 취소한 경우의 주문 id. 전액 취소면 null */
	@Column(name = "order_id", updatable = false)
	private Long orderId;

	/** point3 환불 항목 id. 응답을 받아야 채워진다 */
	@Column(name = "point3_refund_id", length = 128)
	private String point3RefundId;

	/**
	 * <b>하나의 논리적 취소에 하나만 만든다.</b> 재시도한다고 새 키로 바꾸면
	 * 같은 취소가 두 번 실행된다 (point3-api 8절). 24시간 동안 유효하다.
	 */
	@Column(name = "idempotency_key", length = 300, updatable = false)
	private String idempotencyKey;

	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "tax_free_amount", nullable = false)
	private int taxFreeAmount;

	@Column(name = "vat", nullable = false)
	private int vat;

	@Column(name = "reason", length = 200)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "requested_by", nullable = false, length = 10, updatable = false)
	private RefundRequester requestedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private RefundStatus status;

	/**
	 * 정산이 끝나 시스템으로는 취소할 수 없는 건.
	 * {@code SETTLEMENT_DEADLINE_EXCEEDED} 를 받으면 켜고, 셀러가 직접 환불한다.
	 */
	@Column(name = "settled_manual", nullable = false)
	private boolean settledManual;

	private Refund(Long paymentId, Long orderId, String idempotencyKey,
	               RefundTax tax, String reason, RefundRequester requestedBy) {
		this.paymentId = paymentId;
		this.orderId = orderId;
		this.idempotencyKey = idempotencyKey;
		this.amount = tax.amount();
		this.taxFreeAmount = tax.taxFreeAmount();
		this.vat = tax.vat();
		this.reason = truncate(reason);
		this.requestedBy = requestedBy;
		this.status = RefundStatus.PROCESSING;
		this.settledManual = false;
	}

	public static Refund request(Long paymentId, Long orderId, String idempotencyKey,
	                             RefundTax tax, String reason, RefundRequester requestedBy) {
		return new Refund(paymentId, orderId, idempotencyKey, tax, reason, requestedBy);
	}

	/**
	 * 환불 확정. <b>멱등하다</b> — 실시간 처리와 대사 배치가 같은 건을 확정할 수 있다.
	 *
	 * @return 이번 호출로 확정됐으면 true. false 면 이미 끝나 재고를 또 되돌리면 안 된다
	 */
	public boolean markCompleted(String point3RefundId) {
		if (status == RefundStatus.COMPLETED) {
			return false;
		}
		this.status = RefundStatus.COMPLETED;
		if (point3RefundId != null) {
			this.point3RefundId = point3RefundId;
		}
		return true;
	}

	/**
	 * 실패 확정. <b>확인된 실패에만 부른다</b> — 확정 거절 409, 422, 또는 조회로 확인한 failed.
	 *
	 * 타임아웃·5xx·미확정 409 로는 부르지 않는다. 그건 확인이 아니라 모름이고,
	 * 실패로 내리면 실제로 환불된 건을 안 된 것으로 다루게 된다.
	 */
	public boolean markFailed(String reason) {
		if (status == RefundStatus.FAILED) {
			return false;
		}
		if (status == RefundStatus.COMPLETED) {
			throw new IllegalStateException("이미 완료된 취소는 실패로 바꿀 수 없다 (id=" + id + ")");
		}
		this.status = RefundStatus.FAILED;
		this.reason = truncate(reason);
		return true;
	}

	/** 정산돼서 시스템 취소가 불가능하다. 셀러 직접 환불로 넘긴다 */
	public void markSettledManual() {
		this.settledManual = true;
		markFailed("정산 완료 — 셀러 직접 환불");
	}

	/** point3 가 준 항목 id 를 붙인다. 미확정 409 응답에도 실려 올 수 있다 */
	public void attachPoint3Id(String point3RefundId) {
		if (point3RefundId != null && this.point3RefundId == null) {
			this.point3RefundId = point3RefundId;
		}
	}

	private static String truncate(String value) {
		if (value == null || value.length() <= 200) {
			return value;
		}
		return value.substring(0, 200);
	}
}
