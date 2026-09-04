package store.moeum.moeum.saleform.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 폼 생성 요청. 여기 붙은 제약은 형식 검증까지고,
 * 판매 유형(GROUP/SOLO)에 따라 달라지는 규칙은 서비스가 판단한다.
 */
public record SaleFormCreateRequest(

		@NotBlank(message = "제목은 필수입니다")
		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String title,

		@NotBlank(message = "판매 폼 주소는 필수입니다")
		@Size(max = 120, message = "120자를 넘을 수 없습니다")
		@Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "영소문자·숫자·하이픈만 쓸 수 있습니다")
		String slug,

		@NotNull(message = "판매 유형은 필수입니다")
		SaleType saleType,

		@Min(value = 1, message = "1 이상이어야 합니다")
		int stockMax,

		/** GROUP 필수. SOLO 는 무시된다 */
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer targetQty,

		/** 1인당 구매 상한. null 이면 무제한 */
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer maxPerUser,

		LocalDateTime opensAt,

		/** GROUP 필수 */
		LocalDateTime closesAt,

		/** GROUP 에서만 의미가 있다. SOLO 는 무시된다 */
		ShortfallPolicy shortfallPolicy,

		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String shipStartText,

		@Min(value = 0, message = "0 이상이어야 합니다")
		int minOrderAmount,

		/** Lexical JSON (ADR 0001) */
		String descriptionJson,

		Boolean progressPublic,

		@NotEmpty(message = "상품은 1개 이상이어야 합니다")
		@Valid
		List<ProductRequest> products
) {

	public record ProductRequest(

			@NotBlank(message = "상품명은 필수입니다")
			@Size(max = 200, message = "200자를 넘을 수 없습니다")
			String name,

			@Min(value = 0, message = "0 이상이어야 합니다")
			int sortOrder,

			@NotEmpty(message = "옵션은 1개 이상이어야 합니다")
			@Valid
			List<OptionRequest> options
	) {
	}

	public record OptionRequest(

			@NotBlank(message = "옵션명은 필수입니다")
			@Size(max = 100, message = "100자를 넘을 수 없습니다")
			String name,

			/** 1차금 절대값 — 주문 시 결제한다 */
			@Min(value = 0, message = "0 이상이어야 합니다")
			int deposit1Amount,

			/** 2차금 상품 잔금. 1차금이 전액이면 0 */
			@Min(value = 0, message = "0 이상이어야 합니다")
			int deposit2Amount,

			@Min(value = 0, message = "0 이상이어야 합니다")
			int sortOrder
	) {
	}
}
