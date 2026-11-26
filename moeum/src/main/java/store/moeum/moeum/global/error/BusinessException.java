package store.moeum.moeum.global.error;

/**
 * 비즈니스 규칙 위반. 전역 핸들러가 ErrorCode 의 상태값으로 응답한다.
 * 시스템 장애(5xx)와 구분하기 위해 스택트레이스를 채우지 않는다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.message());
	}

	public BusinessException(ErrorCode errorCode, String message) {
		super(message, null, false, false);
		this.errorCode = errorCode;
	}

	/**
	 * 시스템 오류를 업무 오류로 바꿔 알릴 때 쓴다. 응답은 ErrorCode 대로 나가고,
	 * 원래 예외는 cause 로 남아 무엇 때문이었는지 따라갈 수 있다.
	 */
	public BusinessException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.message(), cause, false, false);
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}
}
