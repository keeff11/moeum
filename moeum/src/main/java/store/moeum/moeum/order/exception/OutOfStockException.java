package store.moeum.moeum.order.exception;

import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

/**
 * 조건부 UPDATE 의 영향 행이 0일 때. 품절이거나 마감이다.
 *
 * <b>절대 재시도하지 않는다.</b> 다시 해도 결과가 같고,
 * 200명이 몰린 상황에서 3회씩 재시도하면 DB 를 600번 두들기게 된다.
 */
public class OutOfStockException extends BusinessException {

	private final Long saleFormId;

	public OutOfStockException(Long saleFormId, String message) {
		this(ErrorCode.OUT_OF_STOCK, saleFormId, message);
	}

	/**
	 * 코드를 갈아끼운다. 일시중지처럼 원인이 품절이 아닐 때 쓴다 —
	 * 화면 분기는 메시지가 아니라 code 로 하므로(ErrorResponse) 문구만 바꿔서는 모자란다.
	 *
	 * <b>타입은 그대로 둔다.</b> 새 예외 타입을 만들면 재시도 제외 규칙(RetryConfig)과
	 * 부르는 쪽의 catch 에서 빠져, 결과가 같은 실패를 3회 두들기게 된다.
	 */
	public OutOfStockException(ErrorCode errorCode, Long saleFormId, String message) {
		super(errorCode, message);
		this.saleFormId = saleFormId;
	}

	public Long saleFormId() {
		return saleFormId;
	}
}
