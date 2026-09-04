package store.moeum.moeum.global.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에 노출하는 에러 코드. 도메인 코드는 각 단계에서 추가한다.
 * message 는 사용자에게 그대로 보여도 되는 문장만 쓴다 (내부 정보 · 토큰 노출 금지).
 */
public enum ErrorCode {

	INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	INVALID_TYPE(HttpStatus.BAD_REQUEST, "요청 값의 타입이 올바르지 않습니다."),
	MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 파라미터가 없습니다."),
	MALFORMED_BODY(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
	CONFLICT(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

	// --- 인증 · 로그인 ---
	OAUTH_STATE_MISMATCH(HttpStatus.BAD_REQUEST, "로그인 요청이 유효하지 않습니다. 처음부터 다시 시도해 주세요."),
	OAUTH_DENIED(HttpStatus.BAD_REQUEST, "카카오 로그인에 동의하지 않았습니다."),
	OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "카카오 로그인 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."),

	// --- 셀러 ---
	SELLER_NOT_FOUND(HttpStatus.NOT_FOUND, "셀러 정보를 찾을 수 없습니다."),
	SELLER_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 온보딩을 제출했습니다."),
	SELLER_NOT_APPROVED(HttpStatus.FORBIDDEN, "심사가 승인되어야 판매할 수 있습니다."),
	DUPLICATE_STORE_SLUG(HttpStatus.CONFLICT, "이미 사용 중인 판매공간 주소입니다."),

	// --- 구매자 ---
	BUYER_NOT_FOUND(HttpStatus.NOT_FOUND, "구매자 정보를 찾을 수 없습니다."),
	ADDRESS_REQUIRED(HttpStatus.BAD_REQUEST, "배송지를 먼저 등록해 주세요."),

	// --- 장바구니 ---
	OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "상품 옵션을 찾을 수 없습니다."),
	CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "장바구니 항목을 찾을 수 없습니다."),
	CART_EMPTY(HttpStatus.BAD_REQUEST, "장바구니가 비어 있습니다."),

	// --- 주문 · 재고 ---
	OUT_OF_STOCK(HttpStatus.CONFLICT, "품절되었습니다."),
	SALE_CLOSED(HttpStatus.CONFLICT, "판매가 마감되었습니다."),
	MAX_PER_USER_EXCEEDED(HttpStatus.CONFLICT, "1인당 구매 가능 수량을 넘었습니다."),
	MULTIPLE_SELLERS(HttpStatus.BAD_REQUEST, "한 번에 한 셀러의 상품만 주문할 수 있습니다."),
	ORDER_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
	MIN_ORDER_AMOUNT_NOT_MET(HttpStatus.CONFLICT, "최소 주문 금액을 넘지 않았습니다."),
	TEMPORARY_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 오류입니다. 다시 시도해 주세요."),

	// --- 판매 폼 ---
	SALE_FORM_NOT_FOUND(HttpStatus.NOT_FOUND, "판매 폼을 찾을 수 없습니다."),
	DUPLICATE_SALE_FORM_SLUG(HttpStatus.CONFLICT, "이미 사용 중인 판매 폼 주소입니다."),
	INVALID_SALE_FORM(HttpStatus.BAD_REQUEST, "판매 폼 구성이 올바르지 않습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}
}
