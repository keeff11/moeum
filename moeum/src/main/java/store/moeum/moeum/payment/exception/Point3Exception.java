package store.moeum.moeum.payment.exception;

/**
 * point3 호출 실패의 공통 부모.
 *
 * <b>이 예외를 그대로 잡지 말 것.</b> 항상 {@link Point3FailedException} 과
 * {@link Point3UncertainException} 을 나눠서 잡는다 (D-006).
 * 하나로 잡으면 5xx·타임아웃이 실패로 처리되어, 실제로는 출금된 건을 되돌리게 된다 — 미수금이다.
 */
public abstract class Point3Exception extends RuntimeException {

	protected Point3Exception(String message, Throwable cause) {
		super(message, cause);
	}
}
