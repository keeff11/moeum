package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.buyer.domain.BuyerRefundAccount;

import java.time.LocalDateTime;

/**
 * 환불 계좌 조회·등록 응답.
 *
 * <b>계좌번호 전체는 담지 않는다.</b> 본인의 계좌라도 다시 내려보낼 이유가 없다 —
 * 등록 여부와 어느 계좌인지를 알아볼 수 있으면 화면이 그려진다. 번호를 바꿀 때는
 * 새 번호를 다시 입력받는다.
 */
public record RefundAccountResponse(

		@Schema(description = "은행명", example = "국민은행")
		String bank,

		@Schema(description = "가려진 계좌번호. 뒤 네 자리만 보인다", example = "****6789")
		String accountNoMasked,

		@Schema(description = "예금주", example = "홍길동")
		String holderName,

		@Schema(description = "마지막으로 수정한 시각")
		LocalDateTime updatedAt
) {

	public static RefundAccountResponse from(BuyerRefundAccount account) {
		return new RefundAccountResponse(
				account.getBank(),
				account.maskedAccountNo(),
				account.getHolderName(),
				account.getUpdatedAt()
		);
	}
}
