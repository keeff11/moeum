package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import store.moeum.moeum.payment.exception.Point3RefundConflict;
import store.moeum.moeum.payment.exception.Point3RefundRejected;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3RefundEntry;
import store.moeum.moeum.payment.infra.Point3RefundStatus;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


/**
 * 취소 대사 배치.
 *
 * <b>승인 대사와 결정적으로 다른 점이 하나 있다.</b>
 *
 * <pre>
 *   승인 대사 → 결과 모르면 capture 를 다시 호출한다 (멱등하므로 안전)
 *   취소 대사 → ★ 새 취소를 절대 만들지 않는다. 조회 → resume 만 한다
 * </pre>
 *
 * 여기서 {@code POST /refunds} 를 다시 보내면 같은 취소가 두 번 실행된다.
 * {@code resume} 은 새 취소를 만들지 않고 진행 중인 항목을 밀기만 해서 여러 번 불러도 안전하다.
 *
 * <b>EOB 시간대에는 쉰다.</b> 그 시간엔 point3 가 취소를 처리하지 않아 조회·재개가 의미 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundReconcileBatch {

	private static final int BATCH_SIZE = 50;

	/** 방금 만들어진 건은 건너뛴다. 실시간 처리가 아직 응답을 기다리는 중일 수 있다 */
	private static final Duration SETTLE_DELAY = Duration.ofMinutes(1);

	private final RefundWriter writer;
	private final Point3Client point3Client;
	private final Clock clock;

	@Scheduled(fixedDelayString = "${moeum.batch.refund-reconcile-delay:60000}")
	public void run() {
		int handled = reconcileOnce();
		if (handled > 0) {
			log.info("취소 대사 처리: {}건", handled);
		}
	}

	/** 한 회차. 테스트가 직접 부를 수 있게 열어 둔다 */
	public int reconcileOnce() {
		LocalDateTime now = LocalDateTime.now(clock);
		if (EobWindow.isBlocked(now)) {
			// point3 가 취소를 처리하지 않는 시간대다. 00:30 이후에 다시 돈다
			return 0;
		}

		List<Long> pendingIds = writer.claimPending(now.minus(SETTLE_DELAY), BATCH_SIZE);

		int handled = 0;
		for (Long refundId : pendingIds) {
			if (reconcile(refundId)) {
				handled++;
			}
		}
		return handled;
	}

	/** @return 상태를 확정했으면 true. 아직 모르면 false */
	private boolean reconcile(Long refundId) {
		RefundWriter.RefundSnapshot snapshot = writer.snapshot(refundId);

		Optional<Point3RefundStatus> status;
		try {
			status = point3Client.getRefund(snapshot.sessionId());
		} catch (Point3RefundRejected e) {
			log.error("취소 대사 조회 거부: refundId={}, status={} — 확정하지 않는다", refundId, e.status());
			return false;
		} catch (Point3UncertainException e) {
			log.warn("취소 대사 조회 실패: refundId={} — 다음 회차로 넘긴다", refundId);
			return false;
		}

		if (status.isEmpty()) {
			// 취소 이력이 아예 없다 = 우리 요청이 point3 에 닿지 않았다. 되돌려도 안전하다
			writer.fail(refundId, "point3 에 취소 이력이 없음");
			return true;
		}

		return applyStatus(refundId, snapshot, status.get());
	}

	private boolean applyStatus(Long refundId, RefundWriter.RefundSnapshot snapshot,
	                            Point3RefundStatus status) {
		Optional<Point3RefundEntry> mine = findMine(snapshot, status);

		if (mine.isPresent() && mine.get().isCompleted()) {
			return writer.complete(refundId, mine.get().id());
		}
		if (mine.isPresent() && mine.get().status() == store.moeum.moeum.payment.infra
				.RefundEntryStatus.FAILED) {
			String cause = (mine.get().failure() == null) ? "실패" : mine.get().failure().code();
			writer.fail(refundId, "대사 확인: " + cause);
			return true;
		}

		if (status.hasProcessing()) {
			// 새 취소를 만들지 않고 진행 중인 것을 민다. 여러 번 불러도 안전하다
			return resume(refundId, snapshot);
		}

		// 우리 항목을 못 찾았고 진행 중인 것도 없다. 요청이 닿지 않았을 가능성이 높지만
		// 단정하지 않고 다음 회차에 다시 본다 — 잘못 확정하는 것보다 낫다
		log.info("취소 대사 미확정: refundId={} — 다음 회차로 넘긴다", refundId);
		return false;
	}

	private boolean resume(Long refundId, RefundWriter.RefundSnapshot snapshot) {
		try {
			Point3RefundStatus resumed = point3Client.resumeRefund(snapshot.sessionId());
			Optional<Point3RefundEntry> mine = findMine(snapshot, resumed);

			if (mine.isPresent() && mine.get().isCompleted()) {
				return writer.complete(refundId, mine.get().id());
			}
			return false;
		} catch (Point3RefundConflict | Point3RefundRejected e) {
			log.warn("취소 재개 거부: refundId={} — 다음 회차로 넘긴다", refundId);
			return false;
		} catch (Point3UncertainException e) {
			log.warn("취소 재개 결과 불명: refundId={}", refundId);
			return false;
		}
	}

	/**
	 * point3 응답에서 우리 취소 항목을 찾는다.
	 *
	 * 항목 id 를 알고 있으면 그것으로 집는다. 부분 취소를 여러 번 한 세션이면
	 * 항목이 여럿이라 금액만으로는 구분되지 않는다.
	 * id 를 모르면(요청이 닿기 전에 끊긴 경우) 금액이 같은 항목 하나로 추정한다.
	 */
	private static Optional<Point3RefundEntry> findMine(RefundWriter.RefundSnapshot snapshot,
	                                                    Point3RefundStatus status) {
		if (snapshot.point3RefundId() != null) {
			return status.refunds().stream()
					.filter(entry -> snapshot.point3RefundId().equals(entry.id()))
					.findFirst();
		}
		List<Point3RefundEntry> sameAmount = status.refunds().stream()
				.filter(entry -> entry.amount() != null && entry.amount() == snapshot.amount())
				.toList();
		// 금액이 같은 항목이 둘 이상이면 어느 것이 우리 건인지 알 수 없다. 확정하지 않는다
		return sameAmount.size() == 1 ? Optional.of(sameAmount.get(0)) : Optional.empty();
	}
}
