package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 옵션 재고 수정 요청 (D-054) */
public record OptionStockRequest(

		@Schema(description = "이 옵션의 새 재고 상한. 이미 팔리거나 잡힌 수량보다 적게 줄일 수 없다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "80")
		@NotNull(message = "재고는 필수입니다")
		@Min(value = 0, message = "0 이상이어야 합니다")
		Integer stock
) {
}
