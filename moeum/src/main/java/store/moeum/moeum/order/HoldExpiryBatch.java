package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.payment.PaymentPendingAlerter;
import store.moeum.moeum.payment.PaymentWriter;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.domain.PaymentStatus;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3SessionStatus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 만료된 홀드를 회수한다. 1분마다 돈다.
 *
 * <b>이게 최종 안전망이다.</b> 프론트의 /release 는 브라우저 강제 종료·앱 전환이면 오지 않는다.
 * 이 배치가 없으면 이탈자들이 물고 있는 재고가 영원히 안 풀린다.
 *
 * 두 가지를 지킨다.
 *  - FOR UPDATE SKIP LOCKED 로 인스턴스 간 분산. 남이 잡은 행은 기다리지 않고 건너뛴다
 *  - 멱등 가드. 같은 홀드를 두 번 처리해도 재고가 두 번 돌아가지 않는다
 *
 * <b>결제창까지 간 묶음은 풀기 전에 point3 에 묻는다 (D-063).</b>
 * 구매자가 제때 확정했는데 결과값이 우리에게 안 닿았다면 그건 우리 쪽 문제다.
 * 그런 건은 풀지 않고 CAPTURE_PENDING 으로 넘겨 승인 대사 배치에 맡긴다.
 *
 * <pre>
 *   committed · processing · captured → 풀지 않는다. CAPTURE_PENDING 으로 넘긴다
 *   created · identified · initiated  → 구매자가 늦은 것이다. 푼다
 *   failed · expired · 404 · 토큰 없음 → 푼다
 *   타임아웃 · 5xx · 401/403 · unknown  → 모른다. 이번 회차엔 풀지 않는다
 * </pre>
 *
 * <b>오래 안 물어본 순으로 돌아가며 묻는다 (D-079).</b> 결론이 안 나는 건이 앞자리를 계속 차지하지 않게
 * 물어본 시각을 찍어 줄 뒤로 보낸다. 결론이 안 난 횟수가 기준을 넘으면 Slack 으로 알린다.
 *
 * point3 조회는 트랜잭션 밖에서 한다 (CLAUDE.md 규칙 1). 그래서 이 클래스에는 {@code @Transactional}
 * 이 없고, 회수만 {@link TransactionTemplate} 으로 짧게 묶는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryBatch {

	/** 한 번에 집는 양. 락을 오래 쥐지 않도록 끊어서 처리한다 */
	private static final int BATCH_SIZE = 100;

	/** 한 회차에 point3 에 묻는 건수. 넘치는 건은 다음 회차로 미룬다 — 묻지 않고 풀지 않는다 */
	private static final int CHECK_SIZE = 50;

	private final StockHoldRepository stockHoldRepository;
	private final StockLedger stockLedger;
	private final PaymentRepository paymentRepository;
	private final PaymentWriter paymentWriter;
	private final Point3Client point3Client;
	private final PaymentPendingAlerter alerter;
	private final TransactionTemplate transactionTemplate;

	/**
	 * 끄면 이전 동작으로 돌아간다 — 만료되면 point3 에 묻지 않고 푼다.
	 * 문제가 생기면 {@code HOLD_EXPIRY_POINT3_CHECK=false} 로 재시작해 되돌린다.
	 */
	@Value("${moeum.batch.hold-expiry-point3-check:true}")
	private boolean point3Check;

	/**
	 * 결론이 안 난 조회가 이만큼 쌓이면 Slack 으로 알린다 (D-079). 세션마다 한 번이다.
	 * 1분마다 돌지만 오래 안 본 순으로 돌아가며 묻기 때문에, 밀린 건이 많으면 횟수가 더 천천히 쌓인다.
	 */
	@Value("${moeum.alert.hold-check.miss-threshold:5}")
	private int missAlertThreshold;

	@Scheduled(fixedDelayString = "${moeum.batch.hold-expiry-delay:60000}")
	public void run() {
		int released = expireOnce();
		if (released > 0) {
			log.info("만료 홀드 회수: {}건", released);
		}
	}

	/**
	 * 한 번의 회수. 테스트가 직접 부를 수 있게 열어 둔다.
	 *
	 * CAPTURE_PENDING 인 묶음은 조회 쿼리에서 제외된다 —
	 * 승인 결과를 모르는 상태에서 재고를 남에게 넘기면 초과 판매가 된다.
	 */
	public int expireOnce() {
		Set<Long> cleared = point3Check ? checkPayers() : Set.of();
		Integer released = transactionTemplate.execute(status -> release(cleared));
		return released == null ? 0 : released;
	}

	/**
	 * 결제창까지 간 만료 묶음을 point3 에 묻는다. 트랜잭션 밖이다.
	 *
	 * @return 물어본 결과 풀어도 되는 묶음 id
	 */
	private Set<Long> checkPayers() {
		Set<Long> cleared = new HashSet<>();
		for (PaymentRepository.ExpiredSession row : paymentRepository.findExpiredWithSession(CHECK_SIZE)) {
			Check check;
			boolean stop = false;
			try {
				check = check(row);
			} catch (Point3UncertainException e) {
				// point3 가 응답하지 않는다. 남은 건도 타임아웃만 기다릴 것이라 이번 회차는 그만 묻는다 —
				// 묻지 못한 건은 풀리지 않고, 결제창과 무관한 홀드의 회수는 늦어지지 않는다
				log.warn("만료 전 point3 조회 실패: paymentId={} — 이번 회차 조회를 멈춘다", row.getPaymentId());
				check = Check.miss("응답 없음 (타임아웃 · 5xx)");
				stop = true;
			} catch (RuntimeException e) {
				// 한 건이 터져도 나머지는 계속 본다. 이 묶음은 이번 회차에 풀지 않는다
				log.error("만료 전 구매자 확정 확인 실패: paymentId={} — 이번 회차엔 풀지 않는다",
						row.getPaymentId(), e);
				check = Check.miss("처리 중 예외 (" + e.getClass().getSimpleName() + ")");
			}

			if (check.verdict() == Verdict.RELEASE) {
				cleared.add(row.getOrderGroupId());
			}
			record(row, check);
			if (stop) {
				break;
			}
		}
		return cleared;
	}

	private Check check(PaymentRepository.ExpiredSession row) {
		Point3SessionStatus status;
		try {
			status = point3Client.getSession(row.getSessionId()).status();
		} catch (Point3FailedException e) {
			// 404 는 세션이 없다는 뜻, 0 은 토큰이 없다는 뜻이다. 어느 쪽도 결제가 일어났을 수 없다.
			// 401·403 은 우리 설정 문제라 결제 여부를 모른다
			if (e.status() == 404 || e.status() == 0) {
				return Check.RELEASE;
			}
			log.error("만료 전 point3 조회 거부: paymentId={}, status={} — 풀지 않는다",
					row.getPaymentId(), e.status());
			return Check.miss("조회 거부 (" + e.status() + ")");
		}

		if (status.isPayerCommitted()) {
			if (paymentWriter.rescuePayerCommitted(row.getPaymentId(), row.getSessionId())) {
				log.warn("구매자 확정 확인 — 재고를 풀지 않고 승인 대사로 넘긴다: paymentId={}, status={}",
						row.getPaymentId(), status);
			}
			return Check.HANDED_OVER;
		}
		if (status == Point3SessionStatus.UNKNOWN) {
			log.warn("만료 전 point3 조회 결과가 명세에 없는 상태다: paymentId={} — 풀지 않는다", row.getPaymentId());
			return Check.miss("명세에 없는 상태 (unknown)");
		}
		// created · identified · initiated → 구매자가 제때 마치지 않았다. failed · expired → 확정 실패
		return Check.RELEASE;
	}

	/**
	 * 물어본 시각을 찍어 줄 뒤로 보내고, 결론이 안 난 횟수가 기준에 닿으면 알린다 (D-079).
	 *
	 * 기록이 실패해도 이번 회차 판단은 그대로 간다 — 줄 순서와 알림이 한 회차 늦어질 뿐이다.
	 */
	private void record(PaymentRepository.ExpiredSession row, Check check) {
		try {
			int misses = paymentWriter.recordHoldCheck(row.getPaymentId(), check.missed());
			if (check.missed() && misses >= missAlertThreshold) {
				alerter.holdCheckStuck(row.getPaymentId(), row.getSessionId(), misses, check.reason());
			}
		} catch (RuntimeException e) {
			log.error("만료 전 조회 기록 실패: paymentId={}", row.getPaymentId(), e);
		}
	}

	private enum Verdict {
		/** 결제가 일어나지 않았다. 푼다 */
		RELEASE,
		/** 구매자가 확정했다. 승인 대사로 넘겼다 */
		HANDED_OVER,
		/** 모른다. 이번 회차엔 풀지 않는다 */
		MISS
	}

	private record Check(Verdict verdict, String reason) {

		static final Check RELEASE = new Check(Verdict.RELEASE, null);
		static final Check HANDED_OVER = new Check(Verdict.HANDED_OVER, null);

		static Check miss(String reason) {
			return new Check(Verdict.MISS, reason);
		}

		boolean missed() {
			return verdict == Verdict.MISS;
		}
	}

	private int release(Set<Long> cleared) {
		// 고르기는 오래된 홀드부터(홀드 id 순), 재고 고치기는 판매 폼 id 순이다 (D-086).
		// 한 회차에 여러 폼의 재고를 고치므로, 주문 생성(판매 폼 id 오름차순)과 순서가 엇갈리면 교착이 난다
		List<StockHold> expired = stockHoldRepository.findExpiredForUpdate(BATCH_SIZE).stream()
				.sorted(Comparator.comparing((StockHold hold) -> hold.getSaleForm().getId())
						.thenComparing(StockHold::getId))
				.toList();

		int released = 0;
		for (StockHold hold : expired) {
			Long groupId = hold.getOrder().getOrderGroup().getId();
			// 결제창까지 갔는데 이번 회차에 확인하지 못한 묶음은 남긴다
			if (point3Check && !cleared.contains(groupId) && awaitingPayer(groupId)) {
				continue;
			}
			if (!hold.release()) {
				continue;
			}
			int affected = stockLedger.release(hold);
			if (affected == 0) {
				// held 가 이미 그만큼 없다. 데이터가 어긋난 상태라 조용히 넘기지 않는다
				log.warn("홀드 회수 시 재고 반환 실패: holdId={}, saleFormId={}, qty={}",
						hold.getId(), hold.getSaleForm().getId(), hold.getQty());
				continue;
			}
			hold.getOrder().getOrderGroup().expire();
			released++;
		}
		return released;
	}

	/** 세션이 붙은 채 구매자 확정을 기다리는 1차금이 있는가. 목록을 뽑은 뒤 생겼을 수도 있다 */
	private boolean awaitingPayer(Long groupId) {
		return paymentRepository.findByOrderGroupIdAndPhase(groupId, PaymentPhase.FIRST)
				.map(p -> p.getStatus() == PaymentStatus.CREATED && p.getSessionId() != null)
				.orElse(false);
	}
}
