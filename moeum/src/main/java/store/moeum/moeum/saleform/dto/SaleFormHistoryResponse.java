package store.moeum.moeum.saleform.dto;

import store.moeum.moeum.saleform.domain.SaleFormHistory;

import java.time.LocalDateTime;

public record SaleFormHistoryResponse(
		Long id,
		String field,
		String oldValue,
		String newValue,
		Long changedBy,
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
