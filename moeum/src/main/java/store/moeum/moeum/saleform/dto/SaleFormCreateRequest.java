package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

		@Schema(description = "상품명. 구매자 화면과 카드에 그대로 나온다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "아크릴 스탠드")
		@NotBlank(message = "제목은 필수입니다")
		@Size(max = 200, message = "200자를 넘을 수 없습니다")
		String title,

		@Schema(description = "판매 폼 주소. 영소문자·숫자·하이픈만. 셀러 안에서 중복될 수 없다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "acrylic-stand-2nd")
		@NotBlank(message = "판매 폼 주소는 필수입니다")
		@Size(max = 120, message = "120자를 넘을 수 없습니다")
		@Pattern(regexp = "^[a-z0-9][a-z0-9-]*$", message = "영소문자·숫자·하이픈만 쓸 수 있습니다")
		String slug,

		@Schema(description = "판매 유형. GROUP=공동구매(목표수량·마감 필요), SOLO=단독 판매",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "판매 유형은 필수입니다")
		SaleType saleType,

		@Schema(description = "판매할 총 수량", requiredMode = Schema.RequiredMode.REQUIRED,
				example = "100")
		@Min(value = 1, message = "1 이상이어야 합니다")
		int stockMax,

		@Schema(description = "목표 수량(모집 68/100 의 100). 공동구매는 필수, 단독 판매는 무시된다",
				example = "100")
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer targetQty,

		@Schema(description = "1인당 구매 상한. 비우면 무제한", example = "2")
		@Min(value = 1, message = "1 이상이어야 합니다")
		Integer maxPerUser,

		@Schema(description = "판매 시작 시각. 비우면 판매 시작을 누르는 즉시 열린다")
		LocalDateTime opensAt,

		@Schema(description = "모집 마감 시각. 공동구매는 필수다. 이 시각이 지나면 자동으로 마감된다")
		LocalDateTime closesAt,

		@Schema(description = "목표 수량에 못 미친 채 마감됐을 때 처리. "
				+ "CANCEL=전원 자동 취소·환불, PROCEED=그대로 진행, EXTEND=연장(아직 미구현이라 수동 처리). "
				+ "공동구매에서만 의미가 있고, 비우면 PROCEED 로 본다")
		ShortfallPolicy shortfallPolicy,

		@Schema(description = "발송 시작 안내 문구. 그대로 화면에 나온다",
				example = "8월 20일(월) 순차발송")
		@Size(max = 100, message = "100자를 넘을 수 없습니다")
		String shipStartText,

		@Schema(description = "최소 주문 금액. 0이면 제한 없음", example = "10000")
		@Min(value = 0, message = "0 이상이어야 합니다")
		int minOrderAmount,

		@Schema(description = "상품 상세 설명. Lexical 에디터가 만든 JSON 문자열을 그대로 넣는다")
		String descriptionJson,

		@Schema(description = "모집 현황(68/100)을 구매자에게 보여줄지. 비우면 공개다", example = "true")
		Boolean progressPublic,

		@Schema(description = "노출 순서대로의 이미지 객체 키. 첫 번째가 대표 이미지다. 이미지 업로드 URL 발급으로 "
				+ "받은 objectKey 를 그대로 넣는다. 전체 교체라 보내지 않은 이미지는 지워진다")
		List<@NotBlank(message = "이미지 URL 이 비었습니다")
			 @Size(max = 500, message = "500자를 넘을 수 없습니다") String> images,

		@Schema(description = "상품과 옵션. 1개 이상이어야 한다",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotEmpty(message = "상품은 1개 이상이어야 합니다")
		@Valid
		List<ProductRequest> products
) {

	@Schema(description = "상품 하나와 그 옵션들")
	public record ProductRequest(

			@Schema(description = "상품 이름", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "아크릴 스탠드")
			@NotBlank(message = "상품명은 필수입니다")
			@Size(max = 200, message = "200자를 넘을 수 없습니다")
			String name,

			@Schema(description = "노출 순서. 작을수록 먼저 나온다", example = "0")
			@Min(value = 0, message = "0 이상이어야 합니다")
			int sortOrder,

			@Schema(description = "선택 가능한 옵션. 1개 이상이어야 한다",
					requiredMode = Schema.RequiredMode.REQUIRED)
			@NotEmpty(message = "옵션은 1개 이상이어야 합니다")
			@Valid
			List<OptionRequest> options
	) {
	}

	@Schema(description = "옵션 하나. 가격은 절대값이다 — 기준가에 더하는 추가금이 아니다")
	public record OptionRequest(

			@Schema(description = "옵션 이름", requiredMode = Schema.RequiredMode.REQUIRED,
					example = "블루")
			@NotBlank(message = "옵션명은 필수입니다")
			@Size(max = 100, message = "100자를 넘을 수 없습니다")
			String name,

			@Schema(description = "1차금. 주문할 때 바로 결제되는 금액", example = "20000")
			@Min(value = 0, message = "0 이상이어야 합니다")
			int deposit1Amount,

			@Schema(description = "2차금. 입고 후 청구되는 잔금. 1차금이 전액이면 0", example = "12000")
			@Min(value = 0, message = "0 이상이어야 합니다")
			int deposit2Amount,

			@Schema(description = "노출 순서. 작을수록 먼저 나온다", example = "0")
			@Min(value = 0, message = "0 이상이어야 합니다")
			int sortOrder
	) {
	}
}
