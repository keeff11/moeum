package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 주문 생성 = 재고 홀드. B2 옵션·수량 확정 시 호출한다 (D-001).
 *
 * 단일 상품 즉시구매는 items 가 1개인 특수 케이스다.
 * 장바구니에서 넘어오는 경우 여러 판매 폼이 섞일 수 있다.
 */
public record OrderCreateRequest(

		@Schema(description = "주문할 옵션과 수량. 1개 이상이어야 한다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotEmpty(message = "주문 항목은 1개 이상이어야 합니다")
		@Valid
		List<Item> items
) {

	@Schema(description = "주문 항목 하나")
	public record Item(

			@Schema(description = "상품 옵션 id", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "31")
			@NotNull(message = "옵션은 필수입니다")
			Long optionId,

			@Schema(description = "수량", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
			@Min(value = 1, message = "1 이상이어야 합니다")
			int qty
	) {
	}
}
