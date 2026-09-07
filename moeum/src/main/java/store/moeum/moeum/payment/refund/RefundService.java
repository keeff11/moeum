package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.payment.exception.Point3RefundConflict;
import store.moeum.moeum.payment.exception.Point3RefundRejected;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3RefundEntry;
import store.moeum.moeum.payment.infra.Point3RefundRequest;
import store.moeum.moeum.payment.infra.Point3RefundStatus;
import store.moeum.moeum.payment.infra.RefundConflictCode;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;


/**
 * 취소 실행 엔진.
 *
 * <b>{@code @Transactional} 이 없다.</b> point3 호출을 트랜잭션 밖에 두기 위해서다
 * (CLAUDE.md 규칙 1). DB 쓰기는 {@link RefundWriter} 의 짧은 트랜잭션으로 나간다.
 *
 * <pre>
 *   ① EOB 확인            우리 시계. 막히면 시도하지 않는다
 *   ② refund 행 + 키 저장  ★ 커밋. 승인의 CAPTURE_PENDING 과 같은 자리
 *   ③ 상태 조회            processing 이 있으면 새 취소가 아니라 resume
 *   ④ POST /refunds       트랜잭션 밖
 *   ⑤ 결과 분기
 * </pre>
 *
 * <b>승인과 결정적으로 다른 점은 취소가 멱등하지 않다는 것이다.</b>
 * 타임아웃·5xx·미확정 409 뒤에 다시 요청하면 같은 취소가 두 번 실행된다.
 * 그래서 '모름' 은 전부 {@code PROCESSING} 으로 남기고 대사 배치가 조회 → resume 으로 끝낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundWriter writer;
	private final Point3Client point3Client;
	private final Clock clock;

	/**
	 * 취소를 실행한다.
	 *
	 * @param paymentId 취소할 결제. 1차금·2차금이 별도 세션이라 차수마다 따로 부른다
	 * @param orderId   특정 폼만 취소하면 그 주문 id, 전액이면 null
	 * @param amount    이번에 취소할 금액. 세금 3종은 원 결제 비율로 안분한다
	 */
	public RefundResult refund(Long paymentId, Long orderId, int amount,
	                           String reason, RefundRequester requester) {
		LocalDateTime now = LocalDateTime.now(clock);
		if (EobWindow.isBlocked(now)) {
			// 어차피 EOB_WINDOW_BLOCKED 로 튕긴다. 보내지 않는다
			throw new BusinessException(ErrorCode.REFUND_EOB_BLOCKED);
		}

		RefundWriter.Prepared prepared = writer.prepare(paymentId, orderId, amount, reason, requester);

		// 여기부터 refund 행이 PROCESSING 으로 커밋돼 있다. 서버가 죽어도 대사 배치가 이어받는다
		return execute(prepared);
	}

	/** 취소 가능 여부와 잔액. 취소 화면에 들어왔을 때만 부른다 — 주문 조회 경로에 두지 않는다 */
	public Optional<Point3RefundStatus> inspect(String sessionId) {
		return point3Client.getRefund(sessionId);
	}

	/** 지금 EOB 로 막혀 있으면 풀리는 시각, 아니면 null */
	public LocalDateTime blockedUntil() {
		return EobWindow.nextOpenAt(LocalDateTime.now(clock));
	}

	// ---------------------------------------------------------------- 실행

	private RefundResult execute(RefundWriter.Prepared prepared) {
		// ③ 진행 중인 취소가 있으면 새로 만들지 않는다. resume 으로 이어받아야 한다
		Optional<Point3RefundStatus> status = safeInspect(prepared.sessionId());
		if (status.isPresent() && status.get().hasProcessing()) {
			log.info("진행 중인 취소가 있다. 배치가 재개한다: refundId={}", prepared.refundId());
			return RefundResult.processing(prepared.refundId());
		}
		if (status.isPresent() && !status.get().canCreate()) {
			writer.fail(prepared.refundId(), "취소 불가 상태");
			return RefundResult.failed(prepared.refundId(), "취소할 수 없는 결제입니다.");
		}
		if (status.isPresent() && status.get().refundable() < prepared.tax().amount()) {
			writer.fail(prepared.refundId(), "취소 가능 잔액 초과");
			return RefundResult.failed(prepared.refundId(), "취소 가능 금액을 넘었습니다.");
		}

		// ④ 실제 취소
		try {
			Point3RefundEntry entry = point3Client.refund(
					prepared.sessionId(),
					new Point3RefundRequest(prepared.tax().amount(), prepared.tax().taxFreeAmount(),
							prepared.tax().vat(), reasonOf(prepared)),
					prepared.idempotencyKey());

			if (entry.isCompleted()) {
				writer.complete(prepared.refundId(), entry.id());
				return RefundResult.completed(prepared.refundId());
			}
			// POST 200 은 항상 completed 여야 한다. 아니면 명세가 바뀐 것이므로 확정하지 않는다
			log.warn("취소 200 인데 completed 가 아니다: refundId={}, status={}",
					prepared.refundId(), entry.status());
			writer.attachPoint3Id(prepared.refundId(), entry.id());
			return RefundResult.processing(prepared.refundId());

		} catch (Point3RefundConflict e) {
			return handleConflict(prepared.refundId(), e.code());

		} catch (Point3RefundRejected e) {
			// 422 · 400 · 401 · 404 — 취소가 일어나지 않았다. 되돌려도 안전하다
			log.warn("취소 거절: refundId={}, status={}", prepared.refundId(), e.status());
			writer.fail(prepared.refundId(), "취소 거절(" + e.status() + ")");
			return RefundResult.failed(prepared.refundId(), "취소가 완료되지 않았습니다.");

		} catch (Point3UncertainException e) {
			// 타임아웃 · 5xx. 환불됐을 수 있다 — 다시 요청하지 않는다
			log.error("취소 결과 불명: refundId={} — 재요청하지 않는다", prepared.refundId());
			return RefundResult.processing(prepared.refundId());
		}
	}

	/**
	 * 409 분기 (point3-api 8절).
	 *
	 * <b>'모름' 은 전부 PROCESSING 으로 남긴다.</b> 여기서 새 키로 재요청하면 이중 환불이다.
	 */
	private RefundResult handleConflict(Long refundId, RefundConflictCode code) {
		if (code == RefundConflictCode.SETTLEMENT_DEADLINE_EXCEEDED) {
			// 시스템으로는 취소할 수 없다. 셀러 직접 환불로 넘긴다
			writer.markSettledManual(refundId);
			return RefundResult.settledManual(refundId);
		}
		if (code == RefundConflictCode.EOB_WINDOW_BLOCKED) {
			// ① 에서 걸렀는데도 왔다면 조회와 요청 사이에 23:30 을 넘긴 것이다
			writer.fail(refundId, "EOB 차단");
			throw new BusinessException(ErrorCode.REFUND_EOB_BLOCKED);
		}
		if (code.isConfirmedRejection()) {
			writer.fail(refundId, "취소 거부: " + code);
			return RefundResult.failed(refundId, "취소할 수 없는 결제입니다.");
		}

		log.info("취소 결과 미확정(409 {}): refundId={} — 배치가 확인한다", code, refundId);
		return RefundResult.processing(refundId);
	}

	/** 조회가 실패해도 취소 자체는 시도한다. 조회는 헛걸음을 줄이는 것이지 필수 관문이 아니다 */
	private Optional<Point3RefundStatus> safeInspect(String sessionId) {
		try {
			return point3Client.getRefund(sessionId);
		} catch (RuntimeException e) {
			log.warn("취소 전 조회 실패: {} — 그대로 진행한다", e.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private static String reasonOf(RefundWriter.Prepared prepared) {
		return "구매자 요청";
	}

	// ---------------------------------------------------------------- 결과

	public record RefundResult(Long refundId, Status status, String message) {

		public enum Status {
			/** 환불 완료 */
			COMPLETED,
			/** 결과 확인 중. <b>실패가 아니다</b> — 대사 배치가 끝낸다. 다시 요청하게 하면 안 된다 */
			PROCESSING,
			FAILED,
			/** 정산돼서 시스템 취소 불가. 셀러가 직접 환불한다 */
			SETTLED_MANUAL
		}

		static RefundResult completed(Long id) {
			return new RefundResult(id, Status.COMPLETED, "취소가 완료되었습니다.");
		}

		static RefundResult processing(Long id) {
			return new RefundResult(id, Status.PROCESSING,
					"취소를 처리하고 있습니다. 잠시 후 상태를 확인해 주세요.");
		}

		static RefundResult failed(Long id, String message) {
			return new RefundResult(id, Status.FAILED, message);
		}

		static RefundResult settledManual(Long id) {
			return new RefundResult(id, Status.SETTLED_MANUAL,
					"정산이 완료되어 자동 취소가 어렵습니다. 판매자에게 문의해 주세요.");
		}
	}
}
