package store.moeum.moeum.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 장바구니 담기. saleFormId 는 optionId 로 역추적할 수 있어 받지 않는다 */
public record CartAddRequest(

		@NotNull(message = "옵션은 필수입니다")
		Long optionId,

		@Min(value = 1, message = "1 이상이어야 합니다")
		@Max(value = 999, message = "999를 넘을 수 없습니다")
		int qty
) {
}
