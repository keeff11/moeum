package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송장 등록 (D-047).
 *
 * <b>택배사를 문자열로 받는다.</b> 스마트택배 같은 조회 API 를 붙이면 그쪽 택배사 코드로
 * 맞추는 것이 맞지만, 코드 목록을 그 API 에서 받아 와야 정확하다. 지금 임의로 코드를
 * 정해 두면 나중에 두 체계를 매핑하는 표가 하나 더 생긴다.
 *
 * @param carrier    택배사. 지금은 화면에 보이는 이름 그대로 저장한다
 * @param trackingNo 송장번호. 형식은 택배사마다 달라 검증하지 않는다 —
 *                   자릿수를 우리가 정해 두면 새 택배사가 들어올 때 등록이 막힌다
 */
public record ShipmentRequest(

		@Schema(description = "택배사 이름. 구매자에게 그대로 보인다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "CJ대한통운")
		@NotBlank(message = "택배사는 필수입니다")
		@Size(max = 50, message = "택배사는 50자를 넘을 수 없습니다")
		String carrier,

		@Schema(description = "송장번호. 택배사마다 형식이 달라 자릿수를 검증하지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "123456789012")
		@NotBlank(message = "송장번호는 필수입니다")
		@Size(max = 50, message = "송장번호는 50자를 넘을 수 없습니다")
		String trackingNo
) {
}
