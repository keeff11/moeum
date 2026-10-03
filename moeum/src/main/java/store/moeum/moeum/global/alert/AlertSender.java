package store.moeum.moeum.global.alert;

/**
 * 운영자에게 보내는 알림 (D-068). 구매자 · 셀러 알림(outbox)과는 다른 통로다.
 *
 * <b>예외를 던지지 않는다.</b> 알림이 실패했다고 결제 처리가 멈추면 안 된다.
 */
public interface AlertSender {

	/**
	 * @return 전달했으면 true. 실패했으면 false — 호출자가 다음 회차에 다시 보낼지 정한다
	 */
	boolean send(AlertLevel level, String text);
}
