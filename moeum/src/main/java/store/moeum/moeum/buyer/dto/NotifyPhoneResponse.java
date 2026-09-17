package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.buyer.domain.Buyer;

import java.time.LocalDateTime;

/**
 * 인증을 마친 알림 받을 번호 (D-064).
 *
 * <b>전체 번호를 담지 않는다.</b> 환불 계좌(D-057) · 셀러 화면의 수령인 번호와 같은 취지다 —
 * 응답 본문에 실리면 개발자도구와 캐시에 남는다. 번호를 바꿀 때는 다시 인증한다.
 */
public record NotifyPhoneResponse(

		@Schema(description = "가려진 번호. 가운데만 가린다", example = "010-****-5678")
		String phoneMasked,

		@Schema(description = "인증한 시각")
		LocalDateTime verifiedAt
) {

	public static NotifyPhoneResponse from(Buyer buyer) {
		return new NotifyPhoneResponse(buyer.maskedNotifyPhone(), buyer.getNotifyPhoneVerifiedAt());
	}
}
