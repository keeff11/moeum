package store.moeum.moeum.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 모든 실패 응답의 공통 포맷.
 *
 * <pre>
 * {
 *   "code": "INVALID_INPUT",
 *   "message": "요청 값이 올바르지 않습니다.",
 *   "path": "/api/sale-forms",
 *   "timestamp": "2026-09-01T23:40:12.345+09:00",
 *   "traceId": "9f2c1a4e",
 *   "fieldErrors": [ { "field": "qty", "reason": "1 이상이어야 합니다" } ]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
		String code,
		String message,
		String path,
		OffsetDateTime timestamp,
		String traceId,
		List<FieldError> fieldErrors
) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	public record FieldError(String field, String reason) {
	}

	public static ErrorResponse of(ErrorCode code, String message, String path, String traceId) {
		return new ErrorResponse(code.name(), message, path, OffsetDateTime.now(KST), traceId, null);
	}

	public static ErrorResponse of(ErrorCode code, String message, String path, String traceId,
	                               List<FieldError> fieldErrors) {
		List<FieldError> errors = (fieldErrors == null || fieldErrors.isEmpty()) ? null : fieldErrors;
		return new ErrorResponse(code.name(), message, path, OffsetDateTime.now(KST), traceId, errors);
	}
}
