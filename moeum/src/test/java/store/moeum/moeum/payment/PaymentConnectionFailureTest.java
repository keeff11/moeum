package store.moeum.moeum.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.domain.PaymentActor;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.payment.exception.Point3FailedException;
import store.moeum.moeum.payment.infra.Point3Capture;
import store.moeum.moeum.payment.infra.Point3Client;
import store.moeum.moeum.payment.infra.Point3SessionStatus;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 승인 전후에 DB 커넥션을 얻지 못했을 때의 응답 (D-092).
 *
 * 몰릴 때 커넥션 풀 대기(3초)가 넘으면 트랜잭션 시작이 {@link CannotCreateTransactionException} 으로 실패한다.
 * 실제 풀을 말리기는 어려워 기록 쪽을 목으로 바꿔 던지게 한다. 돈이 나간 뒤 확정을 못 적은 상태에서
 * 대사 배치가 이어받는 부분은 잠금 충돌 테스트(PaymentFlowTest, D-083)가 실제 DB 로 이미 본다.
 */
class PaymentConnectionFailureTest {

	private static final String ORDER_TOKEN = "ord_test";
	private static final String SESSION_ID = "sess_test";
	private static final long PAYMENT_ID = 7L;
	private static final SessionUser BUYER = new SessionUser("kakao-1", "구매자");

	private final PaymentWriter writer = mock(PaymentWriter.class);
	private final Point3Client point3Client = mock(Point3Client.class);
	private final PaymentPendingAlerter alerter = mock(PaymentPendingAlerter.class);
	private final PaymentService service = new PaymentService(writer, point3Client, null, alerter);

	@BeforeEach
	void setUp() {
		when(writer.markCapturePending(BUYER.kakaoId(), ORDER_TOKEN, SESSION_ID, PaymentPhase.FIRST))
				.thenReturn(PaymentWriter.Pending.ready(PAYMENT_ID, 1L, SESSION_ID));
	}

	@Test
	@DisplayName("출금된_뒤_확정이_커넥션을_못_얻으면_500이_아니라_PENDING이다")
	void 확정_커넥션_대기_초과() {
		when(point3Client.capture(SESSION_ID)).thenReturn(new Point3Capture(SESSION_ID, Point3SessionStatus.CAPTURED));
		doThrow(noConnection()).when(writer).finalizeCapture(PAYMENT_ID, PaymentActor.USER);

		PaymentResultResponse result = service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1");

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		// 돈은 나갔다. 실패로 내리면 안 된다
		verify(writer, never()).failConfirmed(anyLong(), anyString(), any());
	}

	@Test
	@DisplayName("출금된_뒤_확정이_도중에_커넥션을_잃어도_PENDING이다")
	void 확정_커넥션_끊김() {
		when(point3Client.capture(SESSION_ID)).thenReturn(new Point3Capture(SESSION_ID, Point3SessionStatus.CAPTURED));
		doThrow(new DataAccessResourceFailureException("Communications link failure"))
				.when(writer).finalizeCapture(PAYMENT_ID, PaymentActor.USER);

		PaymentResultResponse result = service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1");

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
		verify(writer, never()).failConfirmed(anyLong(), anyString(), any());
	}

	@Test
	@DisplayName("point3가_실패를_확정했는데_실패_기록이_커넥션을_못_얻으면_PENDING이다")
	void 실패_기록_커넥션_대기_초과() {
		when(point3Client.capture(SESSION_ID)).thenReturn(new Point3Capture(SESSION_ID, Point3SessionStatus.FAILED));
		doThrow(noConnection()).when(writer).failConfirmed(eq(PAYMENT_ID), anyString(), eq(PaymentActor.USER));

		PaymentResultResponse result = service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1");

		// CAPTURE_PENDING 으로 남았으니 대사 배치가 다시 물어 실패로 내린다
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
	}

	@Test
	@DisplayName("승인이_4xx로_거부됐는데_실패_기록이_커넥션을_못_얻으면_PENDING이다")
	void 거부_기록_커넥션_대기_초과() {
		when(point3Client.capture(SESSION_ID)).thenThrow(new Point3FailedException(400, "bad request"));
		doThrow(noConnection()).when(writer).failConfirmed(eq(PAYMENT_ID), anyString(), eq(PaymentActor.USER));

		PaymentResultResponse result = service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1");

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PENDING);
	}

	@Test
	@DisplayName("실패_기록이_되면_지금처럼_FAILED다")
	void 실패_기록_정상() {
		when(point3Client.capture(SESSION_ID)).thenReturn(new Point3Capture(SESSION_ID, Point3SessionStatus.FAILED));

		PaymentResultResponse result = service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1");

		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.FAILED);
		verify(writer).failConfirmed(eq(PAYMENT_ID), anyString(), eq(PaymentActor.USER));
	}

	@Test
	@DisplayName("승인_직전_기록이_커넥션을_못_얻으면_승인을_부르지_않고_그대로_올려_503이_되게_한다")
	void 승인_직전_커넥션_대기_초과() {
		when(writer.markCapturePending(BUYER.kakaoId(), ORDER_TOKEN, SESSION_ID, PaymentPhase.FIRST))
				.thenThrow(noConnection());

		// 전역 처리기가 TEMPORARY_ERROR(503) 로 바꾼다 (GlobalExceptionHandlerTest)
		assertThatThrownBy(() -> service.confirm(BUYER, ORDER_TOKEN, SESSION_ID, "payer-1"))
				.isInstanceOf(CannotCreateTransactionException.class);
		verify(point3Client, never()).capture(anyString());
	}

	private static CannotCreateTransactionException noConnection() {
		return new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
				new SQLTransientConnectionException(
						"HikariPool-1 - Connection is not available, request timed out after 3000ms."));
	}
}
