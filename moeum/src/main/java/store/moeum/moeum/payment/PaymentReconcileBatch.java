package store.moeum.moeum.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;
import store.moeum.moeum.payment.domain.PaymentActor;
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

	private final PaymentWriter writer;
	private final Point3Client point3Client;

	@Scheduled(fixedDelayString = "${moeum.batch.payment-reconcile-delay:60000}")
	public void run() {
		int handled = reconcileOnce();
		if (handled > 0) {
			log.info("승인 대사 처리: {}건", handled);
		}
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
			if (reconcile(paymentId)) {
				handled++;
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
			writer.failConfirmed(paymentId, "승인 재시도 거부(" + e.status() + ")", PaymentActor.BATCH);
			return true;
		} catch (Point3UncertainException e) {
			log.warn("승인 재시도 결과 불명: paymentId={} — 다음 회차로 넘긴다", paymentId);
			return false;
		}
	}
}
