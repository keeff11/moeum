package store.moeum.moeum.global.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

/**
 * 전역 예외 핸들러. 모든 실패 응답은 {@link ErrorResponse} 한 가지 포맷으로만 나간다.
 *
 * 원칙
 *  - 4xx 는 클라이언트가 고칠 수 있는 정보를 준다.
 *  - 5xx 는 고정 문구만 준다. 예외 메시지에 토큰 · 계좌 · payer_id 가 섞여 나갈 수 있다.
 *  - 서버 로그에는 traceId 를 남겨 응답과 로그를 이어 붙인다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e, HttpServletRequest request) {
		ErrorCode code = e.errorCode();
		String traceId = newTraceId();
		log.warn("[{}] business error {} {} - {}: {}",
				traceId, request.getMethod(), request.getRequestURI(), code, e.getMessage());
		return build(code, e.getMessage(), request, traceId, null);
	}

	/** @Valid 로 검증한 요청 본문 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
	                                                                 HttpServletRequest request) {
		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
				.map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();
		return build(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.message(), request, newTraceId(), fieldErrors);
	}

	/** @Validated 를 붙인 파라미터 검증 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e,
	                                                              HttpServletRequest request) {
		List<ErrorResponse.FieldError> fieldErrors = e.getConstraintViolations().stream()
				.map(v -> new ErrorResponse.FieldError(lastNode(v), v.getMessage()))
				.toList();
		return build(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.message(), request, newTraceId(), fieldErrors);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
	                                                            HttpServletRequest request) {
		List<ErrorResponse.FieldError> fieldErrors =
				List.of(new ErrorResponse.FieldError(e.getParameterName(), "필수 파라미터입니다"));
		return build(ErrorCode.MISSING_PARAMETER, ErrorCode.MISSING_PARAMETER.message(), request, newTraceId(), fieldErrors);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
	                                                        HttpServletRequest request) {
		List<ErrorResponse.FieldError> fieldErrors =
				List.of(new ErrorResponse.FieldError(e.getName(), "타입이 올바르지 않습니다"));
		return build(ErrorCode.INVALID_TYPE, ErrorCode.INVALID_TYPE.message(), request, newTraceId(), fieldErrors);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e,
	                                                       HttpServletRequest request) {
		// 파싱 실패 메시지에는 본문 조각이 그대로 들어간다. 클라이언트에 되돌려주지 않는다.
		return build(ErrorCode.MALFORMED_BODY, ErrorCode.MALFORMED_BODY.message(), request, newTraceId(), null);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
	                                                              HttpServletRequest request) {
		return build(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.message(), request, newTraceId(), null);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
		return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message(), request, newTraceId(), null);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		String traceId = newTraceId();
		log.error("[{}] unhandled error {} {}", traceId, request.getMethod(), request.getRequestURI(), e);
		return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message(), request, traceId, null);
	}

	private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, HttpServletRequest request,
	                                            String traceId, List<ErrorResponse.FieldError> fieldErrors) {
		HttpStatus status = code.status();
		return ResponseEntity.status(status)
				.body(ErrorResponse.of(code, message, request.getRequestURI(), traceId, fieldErrors));
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private static String lastNode(ConstraintViolation<?> violation) {
		String path = violation.getPropertyPath().toString();
		int idx = path.lastIndexOf('.');
		return idx < 0 ? path : path.substring(idx + 1);
	}
}
