package store.moeum.moeum.outbox;

/**
 * 알림을 실제로 내보내는 곳. 지금은 로그뿐이고, 카카오 알림톡이 붙을 자리다.
 *
 * <b>실패는 예외로 알린다.</b> 조용히 삼키면 릴레이가 성공으로 보고 SENT 로 넘겨
 * 다시는 보내지 않는다 — 알림이 곧 결제 요청이라 유실 비용이 크다.
 */
public interface NotificationSender {

	/**
	 * @throws RuntimeException 보내지 못했을 때. 릴레이가 백오프를 걸어 다시 시도한다
	 */
	void send(OutboxMessage message);
}
