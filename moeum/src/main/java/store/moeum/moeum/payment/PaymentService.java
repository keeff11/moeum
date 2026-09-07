package store.moeum.moeum.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.domain.PaymentActor;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.exception.Point3UncertainException;
import store.moeum.moeum.payment.infra.Point3Capture;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3Properties;
import store.moeum.moeum.payment.infra.Point3Session;
import store.moeum.moeum.payment.infra.Point3SessionRequest;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 1차금 결제 오케스트레이션.
 *
 * <b>이 클래스에는 {@code @Transactional} 이 없다.</b> point3 호출을 트랜잭션 밖에 두기 위해서다
 * (CLAUDE.md 규칙 1). DB 쓰기는 전부 {@link PaymentWriter} 의 짧은 트랜잭션으로 나간다.
 *
 * <pre>
 *   TX1  홀드 유효성 · 마감 확인 + payment 준비
 *   ---  point3 세션 생성                       ← 트랜잭션 밖
 *   TX2  sessionId 저장 + PAY_PENDING
 *        ... 구매자가 결제창에서 확정 ...
 *   TX3  검증 4종 + CAPTURE_PENDING 커밋          ← 승인 전에 반드시 커밋 (D-004)
 *   ---  승인 호출                               ← 트랜잭션 밖
 *   TX4  확정 또는 확인된 실패
 * </pre>
 *
 * <b>승인 결과를 모르면 아무것도 하지 않는다.</b> 타임아웃·5xx·processing 은 CAPTURE_PENDING 을
 * 그대로 두고 끝낸다. 대사 배치가 point3 에 물어 확정한다 (D-005).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String ORDER_TOKEN_PREFIX = "ord_";

	private final PaymentWriter writer;
	private final Point3Client point3Client;
	private final Point3Properties point3Properties;

	/**
	 * 결제창을 띄울 세션을 만든다 (payment-flow 8~13번).
	 *
	 * 세션 생성이 실패하면 홀드는 그대로 살아 있다 — 사용자가 다시 시도할 수 있어야 하고,
	 * 여기서 재고를 풀면 15분 타이머의 의미가 없어진다.
	 */
	public PaySessionResponse pay(SessionUser user, String sessionToken) {
		PaymentWriter.Prepared prepared = writer.prepareFirst(user.kakaoId(), sessionToken);

		// 트랜잭션 밖이다. point3 가 느려도 DB 락을 잡지 않는다
		Point3Session session = point3Client.createSession(Point3SessionRequest.general(
				prepared.amount(), prepared.productName(), null));

		String orderToken = newOrderToken();
		writer.attachSession(prepared.paymentId(), session, orderToken);

		return new PaySessionResponse(session.id(), orderToken, prepared.amount(),
				point3Properties.clientId(), prepared.payerId());
	}

	/**
	 * 결제창에서 돌아온 뒤 승인을 확정한다 (payment-flow 17~23번).
	 *
	 * <b>브라우저가 successUrl 에 도달했다는 사실은 결제 성공이 아니다</b> (CLAUDE.md 규칙 6).
	 * 그건 "승인을 요청해도 된다" 는 신호일 뿐이고, 성공 판정은 승인 응답의 captured 로만 한다.
	 *
	 * 금액은 여기서 다시 받지 않는다 — 프론트가 보낸 값을 검증 기준으로 쓰지 않는다 (규칙 5).
	 */
	public PaymentResultResponse confirm(SessionUser user, String orderToken, String sessionId, String payerId) {
		return confirm(user, orderToken, sessionId, payerId, PaymentPhase.FIRST);
	}

	/**
	 * 1차금·2차금이 같은 코드를 탄다. phase 만 다르다 (payment-flow 0절).
	 *
	 * 2차금은 홀드 검증과 홀드 확정이 빠질 뿐, 승인 결과를 다루는 규칙은 완전히 같다.
	 */
	public PaymentResultResponse confirm(SessionUser user, String orderToken, String sessionId,
	                                     String payerId, PaymentPhase phase) {
		PaymentWriter.Pending pending =
				writer.markCapturePending(user.kakaoId(), orderToken, sessionId, phase);

		if (pending.alreadyPaid()) {
			// 복귀 페이지를 새로고침했거나 confirm 이 두 번 들어왔다. 승인을 또 부르지 않는다
			return PaymentResultResponse.paid(orderToken);
		}

		// 여기서부터 CAPTURE_PENDING 이 커밋돼 있다. 서버가 죽어도 대사 배치가 이어받는다
		try {
			Point3Capture capture = point3Client.capture(pending.sessionId());

			if (capture.isCaptured()) {
				writer.finalizeCapture(pending.paymentId(), PaymentActor.USER, payerId);
				return PaymentResultResponse.paid(orderToken);
			}

			if (capture.status().isTerminalFailure()) {
				// point3 가 실패를 확정했다. 되돌려도 안전하다
				writer.failConfirmed(pending.paymentId(),
						"승인 실패: " + capture.status(), PaymentActor.USER);
				return PaymentResultResponse.failed(orderToken, "결제가 완료되지 않았습니다.");
			}

			// processing. 결과를 모른다 — 되돌리지 않는다
			log.info("승인 진행 중: paymentId={}, status={}", pending.paymentId(), capture.status());
			return PaymentResultResponse.pending(orderToken);

		} catch (Point3FailedException e) {
			// 4xx. point3 가 요청을 받아들이지 않았으므로 승인이 일어났을 가능성이 없다
			log.warn("승인 거부(4xx): paymentId={}, status={}", pending.paymentId(), e.status());
			writer.failConfirmed(pending.paymentId(), "승인 거부(" + e.status() + ")", PaymentActor.USER);
			return PaymentResultResponse.failed(orderToken, "결제가 완료되지 않았습니다.");

		} catch (Point3UncertainException e) {
			// 5xx · 타임아웃. 출금됐을 수 있다 — 홀드도 상태도 건드리지 않는다
			log.error("승인 결과 불명: paymentId={} — 되돌리지 않는다", pending.paymentId());
			return PaymentResultResponse.pending(orderToken);
		}
	}

	/** 복귀 페이지가 반복 조회한다. <b>부작용이 없다</b> (D-014) */
	public PaymentResultResponse status(SessionUser user, String orderToken) {
		return writer.readStatus(user.kakaoId(), orderToken, PaymentPhase.FIRST);
	}

	/** 2차금 상태 조회 */
	public PaymentResultResponse secondStatus(SessionUser user, String orderToken) {
		return writer.readStatus(user.kakaoId(), orderToken, PaymentPhase.SECOND);
	}

	/**
	 * 2차금 결제 세션을 만든다 (payment-flow 2절).
	 *
	 * 1차금과 다른 점은 둘뿐이다 — 홀드가 없고, 저장해 둔 payerId 를 프론트에 함께 내려준다.
	 * payerId 는 세션 생성 요청에 넣는 값이 아니라 SDK 의 customerKey 로 쓰이는 값이다
	 * (point3-api 6절). 있으면 인증 단계가 줄고, 없으면 ANONYMOUS 로 진행된다.
	 */
	public PaySessionResponse paySecond(SessionUser user, String orderToken) {
		PaymentWriter.Prepared prepared = writer.prepareSecond(user.kakaoId(), orderToken);

		Point3Session session = point3Client.createSession(Point3SessionRequest.general(
				prepared.amount(), prepared.productName(), null));

		writer.attachSession(prepared.paymentId(), session, orderToken);

		return new PaySessionResponse(session.id(), orderToken, prepared.amount(),
				point3Properties.clientId(), prepared.payerId());
	}

	/** 2차금 승인 확정 */
	public PaymentResultResponse confirmSecond(SessionUser user, String orderToken, String sessionId) {
		return confirm(user, orderToken, sessionId, null, PaymentPhase.SECOND);
	}

	private static String newOrderToken() {
		byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return ORDER_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
