package store.moeum.moeum.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 결제 상태 전이 감사 로그. 전이마다 한 행이다.
 *
 * <b>payment 테이블은 '현재 상태' 만 들고 이력은 여기가 맡는다</b> (D-023).
 * 재결제로 payment 행을 재사용해도 몇 번 시도했고 왜 실패했는지가 여기 남는다.
 *
 * payment 를 연관관계로 매핑하지 않는다. 이력은 결제를 따라다니는 부속이 아니라
 * 조회 전용 기록이고, 결제를 로딩할 때 딸려 올라올 이유가 없다.
 */
@Entity
@Table(name = "payment_event")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentEvent {

	/** reason 컬럼 길이 */
	private static final int REASON_MAX = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "payment_id", nullable = false, updatable = false)
	private Long paymentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 20, updatable = false)
	private PaymentStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 20, updatable = false)
	private PaymentStatus toStatus;

	@Column(name = "reason", length = REASON_MAX, updatable = false)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "actor", nullable = false, length = 10, updatable = false)
	private PaymentActor actor;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PaymentEvent(Long paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
	                     String reason, PaymentActor actor) {
		this.paymentId = paymentId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.reason = truncate(reason);
		this.actor = actor;
	}

	public static PaymentEvent of(Long paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
	                              String reason, PaymentActor actor) {
		return new PaymentEvent(paymentId, fromStatus, toStatus, reason, actor);
	}

	/** 이유가 길다고 결제 처리가 실패하면 안 된다 */
	private static String truncate(String value) {
		if (value == null || value.length() <= REASON_MAX) {
			return value;
		}
		return value.substring(0, REASON_MAX);
	}
}
