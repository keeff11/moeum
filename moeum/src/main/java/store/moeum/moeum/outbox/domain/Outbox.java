package store.moeum.moeum.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 밖으로 내보낼 알림 한 건 (D-012).
 *
 * <b>이 행은 사실을 만든 트랜잭션에서 함께 커밋된다.</b> 결제 확정과 알림 적재가 같은
 * 트랜잭션이라, 결제가 됐는데 알림이 없거나 알림만 있고 결제가 없는 상태가 생기지 않는다.
 * 실제 발송은 {@code OutboxRelayBatch} 가 트랜잭션 밖에서 한다.
 */
@Entity
@Table(name = "outbox")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

	/** 이 횟수만큼 실패하면 DEAD 로 내린다. 그 뒤로는 사람이 본다 */
	public static final int MAX_RETRY = 8;

	private static final int ERROR_MAX = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_type", nullable = false, length = 30, updatable = false)
	private OutboxAggregate aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private Long aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 50, updatable = false)
	private OutboxEventType eventType;

	/**
	 * 발송에 필요한 최소한만 담는다.
	 *
	 * <b>토큰 · PIN · 전체 payerId · 계좌는 넣지 않는다</b> (CLAUDE.md 규칙 10).
	 * 이 값은 DB 에 남고 실패하면 로그에도 실린다.
	 */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OutboxStatus status;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	@Column(name = "last_error", length = ERROR_MAX)
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "sent_at")
	private LocalDateTime sentAt;

	/** 이 시각 전에는 집지 않는다. 실패 백오프와 처리 중 임대를 겸한다 (V6) */
	@Column(name = "next_attempt_at", nullable = false)
	private LocalDateTime nextAttemptAt;

	private Outbox(OutboxAggregate aggregateType, Long aggregateId, OutboxEventType eventType,
	               String payload, LocalDateTime now) {
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.payload = payload;
		this.status = OutboxStatus.PENDING;
		this.retryCount = 0;
		this.createdAt = now;
		this.nextAttemptAt = now;
	}

	public static Outbox of(OutboxAggregate aggregateType, Long aggregateId,
	                        OutboxEventType eventType, String payload, LocalDateTime now) {
		return new Outbox(aggregateType, aggregateId, eventType, payload, now);
	}

	/**
	 * 발송 성공. <b>멱등하다</b> — 임대가 만료돼 두 번 집힌 건이 뒤늦게 성공을 보고할 수 있다.
	 *
	 * @return 이번 호출로 바뀌었으면 true
	 */
	public boolean markSent(LocalDateTime now) {
		if (status == OutboxStatus.SENT) {
			return false;
		}
		this.status = OutboxStatus.SENT;
		this.sentAt = now;
		this.lastError = null;
		return true;
	}

	/**
	 * 발송 실패. 상한을 넘기면 {@code DEAD} 로 내리고 더 시도하지 않는다.
	 *
	 * @param nextAttemptAt 다음 시도 시각. 실패가 쌓일수록 뒤로 민다
	 */
	public void markFailed(String error, LocalDateTime nextAttemptAt) {
		if (status == OutboxStatus.SENT) {
			// 이미 나갔다. 늦게 도착한 실패 보고로 되살리면 알림이 두 번 나간다
			return;
		}
		this.retryCount++;
		this.lastError = truncate(error);
		this.nextAttemptAt = nextAttemptAt;

		if (retryCount >= MAX_RETRY) {
			this.status = OutboxStatus.DEAD;
		}
	}

	/**
	 * 집어가면서 임대를 건다.
	 *
	 * 릴레이가 발송하는 동안 다른 인스턴스가 같은 행을 집으면 알림톡이 두 번 나간다.
	 * 프로세스가 죽으면 이 시각이 지나면서 자동으로 다시 잡힌다.
	 */
	public void lease(LocalDateTime until) {
		this.nextAttemptAt = until;
	}

	public boolean isDead() {
		return status == OutboxStatus.DEAD;
	}

	private static String truncate(String value) {
		if (value == null || value.length() <= ERROR_MAX) {
			return value;
		}
		return value.substring(0, ERROR_MAX);
	}
}
