package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 인증번호를 보냈다 (D-064). 화면이 타이머 두 개를 그리는 데 쓴다 */
public record PhoneVerificationResponse(

		@Schema(description = "이 시각이 지나면 인증번호를 쓸 수 없다 (발송 3분 뒤)")
		LocalDateTime expiresAt,

		@Schema(description = "이 시각부터 다시 요청할 수 있다 (발송 1분 뒤)")
		LocalDateTime resendAvailableAt
) {
}
