package store.moeum.moeum.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;
import store.moeum.moeum.payment.domain.CapturedAfterFailureException;
import store.moeum.moeum.payment.domain.PaymentActor;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3Session;
import store.moeum.moeum.payment.infra.Point3SessionStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 승인 대사 배치. <b>이 프로젝트에서 가장 중요한 배치다</b> (D-005).
 *
 * {@code CAPTURE_PENDING} 은 "승인을 요청했는데 결과를 모른다" 는 뜻이다.
 * 타임아웃·5xx 로 실시간 처리가 끝나지 못한 건들이 여기 쌓이고,
 * 이 배치가 point3 에 물어 확정한다. 그 전까지 아무도 홀드를 풀지 않는다.
 *
 * <b>승인 마감은 커밋된 날의 다음 날 00:00 KST 다.</b> 그 시각을 넘기면 승인할 수 없고
 * 복구할 방법도 없다 — 구매자는 결제했다고 알고 있는데 출금이 안 된 상태로 남는다.
 * 그래서 이 배치가 멈춰 있으면 안 된다.
 *
 * <pre>
 *   captured           → 확정 (실시간 21번을 대신 실행)
 *   failed · expired   → 확인된 실패. 홀드를 푼다
 *   committed          → 승인을 다시 부른다. 멱등하므로 안전하다
 *   processing         → 그대로 둔다. 다음 회차에 다시 본다
 * </pre>
 *
 * <b>결과값이 안 온 결제도 미리 묻는다 (D-091).</b> 구매자가 결제창에서 확정했는데 결과값이 우리에게
 * 안 닿으면 결제는 {@code CAPTURE_PENDING} 이 되지 못하고 {@code CREATED} 에 남는다. 그런 건을 30초마다
 * point3 에 묻고, 구매자가 확정했으면 {@code CAPTURE_PENDING} 으로 올려 <b>같은 회차에서</b> 승인까지 마친다.
 * 재승인을 이 클래스 한 곳에서만 한다는 원칙(D-075)은 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconcileBatch {

	/** 한 회차에 집는 양. 락을 오래 쥐지 않도록 끊어서 처리한다 */
	private static final int BATCH_SIZE = 50;

	/**
	 * 방금 만들어진 건은 건너뛴다.
	 *
	 * 실시간 confirm 이 아직 승인 응답을 기다리는 중일 수 있다.
	 * 곧바로 끼어들면 승인을 두 번 부른다 — 멱등해서 사고는 안 나지만 부를 이유가 없다.
	 */
	private static final Duration SETTLE_DELAY = Duration.ofMinutes(1);

	/**
	 * 선제 조회 한 회차에 묻는 건수 (D-091). 만료 배치의 조회 상한과 같다.
	 * 30초 주기라 point3 호출은 많아야 분당 100건이다. 넘치는 건은 다음 회차로 미룬다.
	 */
	private static final int SESSION_CHECK_SIZE = 50;

	private final PaymentWriter writer;
	private final Point3Client point3Client;
	private final PaymentPendingAlerter alerter;

	/**
	 * 끄면 선제 조회를 하지 않는다 — 1차금은 홀드 만료 때 묻는 이전 동작만 남는다.
	 * 문제가 생기면 {@code PAYMENT_SESSION_CHECK=false} 로 재시작해 되돌린다.
	 */
	@Value("${moeum.batch.payment-session-check:true}")
	private boolean sessionCheck;

	@Scheduled(fixedDelayString = "${moeum.batch.payment-reconcile-delay:60000}")
	public void run() {
		int handled = reconcileOnce();
		if (handled > 0) {
			log.info("승인 대사 처리: {}건", handled);
		}

		// 이번 회차에 확정한 건을 빼고 남은 것만 본다. 알림이 실패해도 다음 회차 대사는 돌아야 한다
		try {
			alerter.checkStuck();
		} catch (RuntimeException e) {
			log.error("미확정 결제 알림 점검 실패", e);
		}
	}

	@Scheduled(fixedDelayString = "${moeum.batch.payment-session-check-delay:30000}")
	public void runSessionCheck() {
		int rescued = sessionCheckOnce();
		if (rescued > 0) {
			log.info("선제 조회로 구매자 확정 확인: {}건", rescued);
		}
	}

	/**
	 * 선제 조회 한 회차 (D-091). 테스트가 직접 부를 수 있게 열어 둔다.
	 *
	 * <pre>
	 *   committed          → CAPTURE_PENDING 으로 올리고 바로 승인을 부른다
	 *   captured           → CAPTURE_PENDING 으로 올리고 확정한다 (승인은 다시 부르지 않는다)
	 *   processing         → CAPTURE_PENDING 으로 올린다. 이후는 대사가 본다
	 *   failed · expired · 404 → 더 묻지 않는다. 홀드 회수는 만료 배치가 하던 대로 한다
	 *   created · identified · initiated · unknown · 401/403 → 다음 간격에 다시 묻는다
	 *   타임아웃 · 5xx     → 이번 회차를 멈춘다. 남은 건도 타임아웃만 기다릴 것이다
	 * </pre>
	 *
	 * <b>되돌리는 것이 없다.</b> 결론이 실패여도 결제 상태와 홀드는 건드리지 않는다 — 구매자가 창을 닫았다가
	 * 홀드 시간 안에 다시 결제할 수 있고, 재고 회수는 만료 배치의 몫이다.
	 *
	 * @return 구매자 확정을 확인해 넘긴 건수
	 */
	public int sessionCheckOnce() {
		if (!sessionCheck) {
			return 0;
		}
		LocalDateTime now = LocalDateTime.now(JpaAuditingConfig.KST);
		List<PaymentRepository.SessionCheck> due = writer.claimSessionChecks(now, SESSION_CHECK_SIZE);

		int rescued = 0;
		for (PaymentRepository.SessionCheck row : due) {
			try {
				if (checkSession(row)) {
					rescued++;
				}
			} catch (Point3UncertainException e) {
				log.warn("선제 조회 실패: paymentId={} — 이번 회차 조회를 멈춘다", row.getPaymentId());
				break;
			} catch (CapturedAfterFailureException e) {
				alerter.capturedAfterFailure(e.paymentId(), e.sessionId(), PaymentActor.BATCH);
			} catch (RuntimeException e) {
				// 한 건이 터져도 나머지는 계속 본다. 이 건은 다음 간격에 다시 묻는다
				log.error("선제 조회 처리 실패: paymentId={}", row.getPaymentId(), e);
			}
		}
		return rescued;
	}

	/** @return 구매자 확정을 확인해 CAPTURE_PENDING 으로 넘겼으면 true */
	private boolean checkSession(PaymentRepository.SessionCheck row) {
		Point3SessionStatus status;
		try {
			status = point3Client.getSession(row.getSessionId()).status();
		} catch (Point3FailedException e) {
			if (e.status() == 404) {
				// 세션이 없다. 이 세션으로 결제가 일어났을 수 없다
				writer.closeSessionCheck(row.getPaymentId());
				return false;
			}
			// 401·403 은 우리 설정 문제라 결제 여부를 모른다. 고칠 때까지 다음 간격에 다시 묻는다
			log.error("선제 조회 거부: paymentId={}, status={} — 다음 간격에 다시 묻는다",
					row.getPaymentId(), e.status());
			if (e.isCredentialRejected()) {
				alerter.point3Rejected(row.getPaymentId(), e.status());
			}
			return false;
		}

		if (status.isTerminalFailure()) {
			writer.closeSessionCheck(row.getPaymentId());
			return false;
		}
		if (!status.isPayerCommitted()) {
			// 구매자가 아직 결제창에 있거나 떠났다. 다음 간격에 다시 묻는다
			return false;
		}

		if (!writer.rescueOnSessionCheck(row.getPaymentId(), row.getSessionId())) {
			// 그 사이 confirm 이 처리했거나, 재결제로 세션이 바뀌었거나, 홀드가 회수됐다
			return false;
		}
		log.warn("결과값 없이 구매자 확정 확인 — 승인 대사로 넘긴다: paymentId={}, status={}",
				row.getPaymentId(), status);
		// 방금 받은 상태로 바로 이어서 처리한다. 자정 승인 기한이 있어 다음 회차(1분 유예)를 기다리지 않는다
		applyStatus(row.getPaymentId(), status);
		return true;
	}

	/**
	 * 한 회차. 테스트가 직접 부를 수 있게 열어 둔다.
	 *
	 * <b>point3 조회는 트랜잭션 밖에서 한다</b> (CLAUDE.md 규칙 1).
	 * 미확정 건 목록만 잠금 조회로 집어오고, 조회와 확정은 건별로 따로 처리한다.
	 */
	public int reconcileOnce() {
		LocalDateTime threshold = LocalDateTime.now(JpaAuditingConfig.KST).minus(SETTLE_DELAY);
		List<Long> pendingIds = writer.claimPending(threshold, BATCH_SIZE);

		int handled = 0;
		for (Long paymentId : pendingIds) {
			try {
				if (reconcile(paymentId)) {
					handled++;
				}
			} catch (CapturedAfterFailureException e) {
				// 집은 뒤 확정 전에 실패로 내려간 건이다 (D-075). 상태는 그대로 두고 사람에게 넘긴다.
				// FAILED 는 다시 집히지 않으므로 이 알림은 한 번만 나간다
				alerter.capturedAfterFailure(e.paymentId(), e.sessionId(), PaymentActor.BATCH);
			}
		}
		return handled;
	}

	/** @return 상태를 확정했으면 true. 아직 모르면 false */
	private boolean reconcile(Long paymentId) {
		String sessionId = writer.sessionIdOf(paymentId);
		if (sessionId == null) {
			// 세션을 붙이기 전에 죽은 건이다. 승인이 일어났을 수 없으므로 실패로 확정해도 안전하다
			writer.failConfirmed(paymentId, "세션 없음", PaymentActor.BATCH);
			return true;
		}

		Point3Session session;
		try {
			session = point3Client.getSession(sessionId);
		} catch (Point3FailedException e) {
			// 4xx. 없는 세션이거나 자격증명 문제다 — 승인이 일어났을 가능성은 없다.
			// 다만 401·403 은 우리 쪽 설정 문제라 실패로 확정하면 멀쩡한 결제를 죽인다
			if (e.status() == 404) {
				writer.failConfirmed(paymentId, "세션 없음(404)", PaymentActor.BATCH);
				return true;
			}
			log.error("대사 조회 거부: paymentId={}, status={} — 확정하지 않는다", paymentId, e.status());
			if (e.isCredentialRejected()) {
				alerter.point3Rejected(paymentId, e.status());
			}
			return false;
		} catch (Point3UncertainException e) {
			// point3 가 응답하지 않는다. 다음 회차에 다시 본다
			log.warn("대사 조회 실패: paymentId={} — 다음 회차로 넘긴다", paymentId);
			return false;
		}

		return applyStatus(paymentId, session.status());
	}

	private boolean applyStatus(Long paymentId, Point3SessionStatus status) {
		if (status == Point3SessionStatus.CAPTURED) {
			// 실시간 21번이 누락된 건이다. 배치가 대신 확정한다
			return writer.finalizeCapture(paymentId, PaymentActor.BATCH);
		}

		if (status.isTerminalFailure()) {
			writer.failConfirmed(paymentId, "대사 확인: " + status, PaymentActor.BATCH);
			return true;
		}

		if (status == Point3SessionStatus.COMMITTED) {
			// 구매자는 확정했는데 승인 호출이 닿지 않았다. 승인은 멱등하므로 다시 부른다
			return retryCapture(paymentId);
		}

		// processing · created · identified · initiated · unknown — 아직 모른다.
		// 되돌리지 않고 다음 회차에 다시 본다
		return false;
	}

	private boolean retryCapture(Long paymentId) {
		String sessionId = writer.sessionIdOf(paymentId);
		try {
			if (point3Client.capture(sessionId).isCaptured()) {
				return writer.finalizeCapture(paymentId, PaymentActor.BATCH);
			}
			return false;
		} catch (Point3FailedException e) {
			if (e.isCredentialRejected()) {
				// 조회는 통과했는데 승인만 거부됐다 — 권한 범위 문제일 수 있다. 고칠 때까지 기다린다 (D-082)
				log.error("승인 재시도 자격증명 거부: paymentId={}, status={} — 확정하지 않는다",
						paymentId, e.status());
				alerter.point3Rejected(paymentId, e.status());
				return false;
			}
			writer.failConfirmed(paymentId, "승인 재시도 거부(" + e.status() + ")", PaymentActor.BATCH);
			return true;
		} catch (Point3UncertainException e) {
			log.warn("승인 재시도 결과 불명: paymentId={} — 다음 회차로 넘긴다", paymentId);
			return false;
		}
	}
}
