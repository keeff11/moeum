package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.SaleFormHistory;

import java.time.LocalDateTime;

public record SaleFormHistoryResponse(
		@Schema(description = "이력 id", example = "3")
		Long id,

		@Schema(description = "바뀐 항목 이름", example = "closesAt")
		String field,

		@Schema(description = "바뀌기 전 값. 비어 있었으면 null")
		String oldValue,

		@Schema(description = "바뀐 뒤 값")
		String newValue,

		@Schema(description = "바꾼 셀러 id", example = "1")
		Long changedBy,

		@Schema(description = "바꾼 시각")
		LocalDateTime createdAt
) {

	public static SaleFormHistoryResponse from(SaleFormHistory history) {
		return new SaleFormHistoryResponse(
				history.getId(),
				history.getField(),
				history.getOldValue(),
				history.getNewValue(),
				history.getChangedBy(),
				history.getCreatedAt()
		);
	}
}
