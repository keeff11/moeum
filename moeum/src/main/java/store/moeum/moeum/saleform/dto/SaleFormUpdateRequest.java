package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 폼 수정 요청. 전체 교체(PUT)다 — 보내지 않은 필드는 null 로 지워진다.
 * slug · saleType · 상품 · 옵션은 수정 대상이 아니다 ({@link SaleFormUpdate} 참고).
 */
public record SaleFormUpdateRequest(

		@Schema(description = "상품명", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "아크릴 스탠드")
		@NotBlank(message = "제목은 필수입니다")
		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String title,

		@Schema(description = "판매할 총 수량. 이미 팔린 수량보다 적게 줄일 수 없다", example = "100")
		@Min(value = 1, message = "1 이상이어야 합니다")
		int stockMax,

		@Schema(description = "목표 수량. 단독 판매는 무시된다", example = "100")
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer targetQty,

		@Schema(description = "1인당 구매 상한. 비우면 무제한", example = "2")
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer maxPerUser,

		@Schema(description = "판매 시작 시각")
		LocalDateTime opensAt,

		@Schema(description = "모집 마감 시각")
		LocalDateTime closesAt,

		@Schema(description = "목표 미달 시 처리. CANCEL=자동 취소, PROCEED=그대로 진행, EXTEND=연장(미구현)")
		ShortfallPolicy shortfallPolicy,

		@Schema(description = "발송 시작 안내 문구", example = "8월 20일(월) 순차발송")
		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String shipStartText,

		@Schema(description = "최소 주문 금액. 0이면 제한 없음", example = "10000")
		@Min(value = 0, message = "0 이상이어야 합니다")
		int minOrderAmount,

		@Schema(description = "상품 상세 설명. Lexical 에디터 JSON 문자열")
		String descriptionJson,

		@Schema(description = "모집 현황을 구매자에게 보여줄지. 비우면 공개다", example = "true")
		Boolean progressPublic,

		@Schema(description = "노출 순서대로의 이미지 객체 키. 첫 번째가 대표 이미지다. 이미지 업로드 URL 발급으로 "
				+ "받은 objectKey 를 그대로 넣는다. 전체 교체라 보내지 않은 이미지는 지워진다")
		List<@NotBlank(message = "이미지 URL 이 비었습니다")
			 @Size(max = 500, message = "500자를 넘을 수 없습니다") String> images
) {

	public SaleFormUpdate toCommand() {
		return new SaleFormUpdate(title, stockMax, targetQty, maxPerUser, opensAt, closesAt,
				shortfallPolicy, shipStartText, minOrderAmount, descriptionJson, progressPublic, images);
	}
}
