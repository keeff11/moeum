package store.moeum.moeum.outbox.infra;

/**
 * SOLAPI 호출 실패의 뿌리.
 *
 * 릴레이는 이걸 잡지 않는다 — 예외가 올라가면 백오프를 걸고 다시 시도한다.
 * 나누는 이유는 로그에서 "다시 해도 소용없는 것" 과 "다시 하면 될 수 있는 것" 을
 * 구분하기 위해서다.
 */
public abstract class SolapiException extends RuntimeException {

	protected SolapiException(String message) {
		super(message);
	}

	protected SolapiException(String message, Throwable cause) {
		super(message, cause);
	}
}
