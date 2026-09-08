package store.moeum.moeum.outbox.domain;

/** outbox.status */
public enum OutboxStatus {

	/** 아직 못 보냈다. 릴레이가 집어간다 */
	PENDING,
	SENT,
	/** 재시도 상한을 넘겼다. <b>사람이 봐야 한다</b> — 알림이 곧 결제 요청이라 그냥 버릴 수 없다 */
	DEAD
}
