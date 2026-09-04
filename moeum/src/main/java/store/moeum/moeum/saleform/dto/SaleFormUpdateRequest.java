package store.moeum.moeum.saleform.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;

import java.time.LocalDateTime;

/**
 * 판매 폼 수정 요청. 전체 교체(PUT)다 — 보내지 않은 필드는 null 로 지워진다.
 * slug · saleType · 상품 · 옵션은 수정 대상이 아니다 ({@link SaleFormUpdate} 참고).
 */
public record SaleFormUpdateRequest(

		@NotBlank(message = "제목은 필수입니다")
		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String title,

		@Min(value = 1, message = "1 이상이어야 합니다")
		int stockMax,

		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer targetQty,

		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer maxPerUser,

		LocalDateTime opensAt,

		LocalDateTime closesAt,

		ShortfallPolicy shortfallPolicy,

		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String shipStartText,

		@Min(value = 0, message = "0 이상이어야 합니다")
		int minOrderAmount,

		String descriptionJson,

		Boolean progressPublic
) {

	public SaleFormUpdate toCommand() {
		return new SaleFormUpdate(title, stockMax, targetQty, maxPerUser, opensAt, closesAt,
				shortfallPolicy, shipStartText, minOrderAmount, descriptionJson, progressPublic);
	}
}
