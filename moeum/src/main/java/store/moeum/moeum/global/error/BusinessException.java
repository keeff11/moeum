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

	public ErrorCode errorCode() {
		return errorCode;
	}
}
