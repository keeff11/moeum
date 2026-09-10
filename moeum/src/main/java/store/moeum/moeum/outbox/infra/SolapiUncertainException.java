package store.moeum.moeum.outbox.infra;

/**
 * 결과 불명 — 5xx · 타임아웃 · 건별 거절.
 *
 * <b>실제로 나갔을 수 있다.</b> 그래서 재시도하면 알림이 두 번 갈 수 있는데,
 * 그 편이 2차금 청구가 영영 안 가는 것보다 낫다고 본다. SOLAPI 쪽
 * {@code allowDuplicates=false} 가 같은 내용의 중복 접수를 한 겹 더 막는다.
 */
public class SolapiUncertainException extends SolapiException {

	public SolapiUncertainException(String message) {
		super(message);
	}

	public SolapiUncertainException(String message, Throwable cause) {
		super(message, cause);
	}
}
