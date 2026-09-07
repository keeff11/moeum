package store.moeum.moeum.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;
import store.moeum.moeum.order.domain.OrderGroup;

import java.time.LocalDateTime;

/**
 * 결제 한 건. 주문묶음 × 차수당 하나다 ({@code uk_payment_group_phase}).
 *
 * <b>상태를 바꾸는 메서드는 전부 전이 조건을 스스로 검사한다.</b> 서비스가 순서를 틀려도
 * 엔티티에서 막히게 하려는 것이다 — 이 도메인에서 잘못된 전이 하나는 돈이다.
 *
 * 특히 {@link #markCaptured()} 는 멱등하고, {@link #markFailed(String)} 는
 * {@code CAPTURE_PENDING} 을 실패로 내리지 않는다. 결과를 모르는 건을 실패로 확정하면
 * 홀드가 풀려 재고가 남에게 팔리고, 구매자는 돈만 나간 상태가 된다.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_group_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_payment_group"))
	private OrderGroup orderGroup;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase", nullable = false, length = 10, updatable = false)
	private PaymentPhase phase;

	/**
	 * point3 sessionId. 재결제로 세션을 새로 만들면 갈아끼운다 (D-023).
	 * 값 자체에 서명이 없어 진위를 못 가린다 — 여기 저장한 문자열과 비교하는 것이 유일한 검증이다.
	 */
	@Column(name = "session_id", length = 128)
	private String sessionId;

	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "supply_amount")
	private Integer supplyAmount;

	@Column(name = "vat")
	private Integer vat;

	@Column(name = "tax_free_amount", nullable = false)
	private int taxFreeAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status;

	@Column(name = "fail_reason", length = 100)
	private String failReason;

	@Column(name = "refunded_amount", nullable = false)
	private int refundedAmount;

	@Column(name = "captured_at")
	private LocalDateTime capturedAt;

	private Payment(OrderGroup orderGroup, PaymentPhase phase, int amount) {
		this.orderGroup = orderGroup;
		this.phase = phase;
		this.amount = amount;
		this.taxFreeAmount = 0;
		this.refundedAmount = 0;
		this.status = PaymentStatus.CREATED;
	}

	public static Payment create(OrderGroup orderGroup, PaymentPhase phase, int amount) {
		return new Payment(orderGroup, phase, amount);
	}

	/**
	 * point3 세션을 붙인다. 세금 내역은 point3 가 계산한 값을 그대로 받는다 —
	 * 우리가 다시 계산하면 {@code amount = supply + taxFree + vat} 불변식이 어긋난다.
	 */
	public void attachSession(String sessionId, Integer supplyAmount, Integer vat, Integer taxFreeAmount) {
		this.sessionId = sessionId;
		this.supplyAmount = supplyAmount;
		this.vat = vat;
		if (taxFreeAmount != null) {
			this.taxFreeAmount = taxFreeAmount;
		}
	}

	/**
	 * 재결제로 이 행을 다시 쓴다 (D-023).
	 *
	 * <b>FAILED 일 때만 허용한다.</b> CAPTURE_PENDING 을 덮으면 실제로 출금된 건의
	 * 유일한 기록이 사라진다. CREATED 는 아직 진행 중이고 CAPTURED 는 이미 끝났다.
	 *
	 * @throws IllegalStateException 재사용할 수 없는 상태일 때
	 */
	public void resetForRetry(int amount) {
		if (!status.isReusable()) {
			throw new IllegalStateException(
					"재사용할 수 없는 결제 상태다: " + status + " (id=" + id + ")");
		}
		this.sessionId = null;
		this.supplyAmount = null;
		this.vat = null;
		this.taxFreeAmount = 0;
		this.amount = amount;
		this.failReason = null;
		this.status = PaymentStatus.CREATED;
	}

	/**
	 * 승인을 부르기 직전에 커밋할 상태 (D-004).
	 *
	 * 이미 PENDING 이면 그대로 둔다 — confirm 이 두 번 들어와도 한 번만 처리되게 하는 가드다.
	 *
	 * @return 이번 호출로 상태가 바뀌었으면 true. false 면 이미 진행 중이라 승인을 또 부르지 않는다
	 */
	public boolean markCapturePending() {
		if (status == PaymentStatus.CAPTURE_PENDING) {
			return false;
		}
		if (status != PaymentStatus.CREATED) {
			throw new IllegalStateException(
					"승인을 요청할 수 없는 상태다: " + status + " (id=" + id + ")");
		}
		this.status = PaymentStatus.CAPTURE_PENDING;
		return true;
	}

	/**
	 * 출금 확정. <b>멱등하다</b> — 실시간 처리와 대사 배치가 같은 건을 확정할 수 있다.
	 *
	 * @return 이번 호출로 확정됐으면 true. false 면 이미 확정돼 있어 재고를 또 차감하면 안 된다
	 */
	public boolean markCaptured() {
		if (status == PaymentStatus.CAPTURED) {
			return false;
		}
		this.status = PaymentStatus.CAPTURED;
		this.capturedAt = LocalDateTime.now(JpaAuditingConfig.KST);
		this.failReason = null;
		return true;
	}

	/**
	 * 확정 실패. <b>CAPTURE_PENDING 은 실패로 내리지 않는다</b> (CLAUDE.md 규칙 3).
	 *
	 * 결과를 모르는 건을 실패로 확정하면 홀드가 풀려 재고가 남에게 팔리고,
	 * 실제로 출금됐다면 구매자는 돈만 나간 상태가 된다.
	 * 그런 건은 대사 배치가 point3 에 물어본 뒤에만 확정한다.
	 *
	 * @return 이번 호출로 실패 처리됐으면 true
	 */
	public boolean markFailed(String reason) {
		if (status == PaymentStatus.FAILED) {
			return false;
		}
		if (status == PaymentStatus.CAPTURED) {
			throw new IllegalStateException("이미 출금된 결제는 실패로 바꿀 수 없다 (id=" + id + ")");
		}
		if (status == PaymentStatus.CAPTURE_PENDING) {
			throw new IllegalStateException(
					"승인 결과를 모르는 결제는 실패로 바꿀 수 없다. 대사 배치가 확정한다 (id=" + id + ")");
		}
		this.status = PaymentStatus.FAILED;
		this.failReason = truncate(reason);
		return true;
	}

	/**
	 * <b>실패가 확인됐을 때만</b> 부른다. CAPTURE_PENDING 도 내릴 수 있는 유일한 통로다.
	 *
	 * 확인됐다는 것은 둘 중 하나다.
	 *  - 승인 호출이 4xx 를 받았다 (point3 가 요청을 받아들이지 않았다)
	 *  - 대사 배치가 세션을 조회해 failed · expired 를 봤다 (D-005)
	 *
	 * 타임아웃·5xx 로는 절대 부르지 않는다. 그건 확인이 아니라 모름이다.
	 */
	public boolean markFailedConfirmed(String reason) {
		if (status == PaymentStatus.FAILED) {
			return false;
		}
		if (status == PaymentStatus.CAPTURED) {
			throw new IllegalStateException("이미 출금된 결제는 실패로 바꿀 수 없다 (id=" + id + ")");
		}
		this.status = PaymentStatus.FAILED;
		this.failReason = truncate(reason);
		return true;
	}

	public boolean isFirst() {
		return phase == PaymentPhase.FIRST;
	}

	/** fail_reason 은 100자다. 이유가 길다고 결제 처리가 실패하면 안 된다 */
	private static String truncate(String reason) {
		if (reason == null) {
			return null;
		}
		return reason.length() <= 100 ? reason : reason.substring(0, 100);
	}
}
