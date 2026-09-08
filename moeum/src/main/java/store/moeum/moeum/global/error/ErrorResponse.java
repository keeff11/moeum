package store.moeum.moeum.global.error;

import io.swagger.v3.oas.annotations.media.Schema;
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
		@Schema(description = "에러 코드. 화면 분기는 메시지가 아니라 이 값으로 한다",
				example = "OUT_OF_STOCK")
		String code,

		@Schema(description = "사용자에게 그대로 보여도 되는 안내 문구",
				example = "품절되었습니다.")
		String message,

		@Schema(description = "오류가 난 요청 경로", example = "/checkout-sessions")
		String path,

		@Schema(description = "오류가 난 시각")
		OffsetDateTime timestamp,

		@Schema(description = "서버 로그를 찾는 추적 id. 문의할 때 이 값을 알려주면 된다")
		String traceId,

		@Schema(description = "입력값 검증 실패일 때만 채워진다. 폼 항목별로 오류를 표시할 때 쓴다")
		List<FieldError> fieldErrors
) {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Schema(description = "입력값 검증에 걸린 항목 하나")
	public record FieldError(
			@Schema(description = "요청 본문의 항목 이름", example = "phone") String field,
			@Schema(description = "무엇이 잘못됐는지", example = "휴대폰 번호 형식이 아닙니다")
			String reason) {
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
