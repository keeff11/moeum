package store.moeum.moeum.outbox.infra;

/**
 * 결과 불명 — 5xx · 타임아웃 · 건별 거절.
 *
 * <b>실제로 나갔을 수 있다.</b> 그래서 재시도하면 알림이 두 번 갈 수 있는데,
 * 그 편이 2차금 청구가 영영 안 가는 것보다 낫다고 본다.
 *
 * <b>SOLAPI 쪽에서 막아 주지 않는다 (D-077).</b> {@code allowDuplicates} 는 "중복 수신번호 허용 여부" 로
 * 한 요청 안의 같은 번호만 거른다(오류 1026). 재시도는 별개 요청이라 그대로 접수된다.
 * 발송 API 에 멱등 키도 없다.
 */
public class SolapiUncertainException extends SolapiException {

	public SolapiUncertainException(String message) {
		super(message);
	}

	public SolapiUncertainException(String message, Throwable cause) {
		super(message, cause);
	}
}
