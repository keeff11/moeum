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
		super(ErrorCode.OUT_OF_STOCK, message);
		this.saleFormId = saleFormId;
	}

	public Long saleFormId() {
		return saleFormId;
	}
}
