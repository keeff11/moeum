package store.moeum.moeum.outbox.infra;

/**
 * 확정 실패 — 4xx, 설정 누락, 서명 오류.
 *
 * 다시 보내도 같은 결과다. 그래도 릴레이는 재시도한다 —
 * 설정을 고치면 그다음 시도에서 나가고, 8회를 다 쓰면 DEAD 로 남아 사람이 본다.
 */
public class SolapiFailedException extends SolapiException {

	public SolapiFailedException(String message) {
		super(message);
	}

	public SolapiFailedException(String message, Throwable cause) {
		super(message, cause);
	}
}
