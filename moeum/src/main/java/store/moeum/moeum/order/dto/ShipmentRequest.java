package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 송장 등록 (D-047).
 *
 * <b>이름과 코드를 같이 받는다</b> (D-048). 이름은 화면에 그대로 보여 주는 값이고,
 * 코드는 스마트택배 배송조회가 쓰는 {@code t_code} 다. 화면은 {@code GET /seller/carriers}
 * 가 준 목록에서 고르므로 둘이 어긋날 일이 없다.
 *
 * <b>코드는 선택이다.</b> 배송조회 키가 없는 환경에서는 화면이 목록을 못 받는데,
 * 그렇다고 송장 등록 자체를 막을 수는 없다 — 그때는 배송조회만 안 된다.
 *
 * @param carrier     택배사. 화면에 보이는 이름 그대로 저장한다
 * @param carrierCode 스마트택배 택배사 코드. 없으면 배송조회를 제공하지 않는다
 * @param trackingNo 송장번호. 형식은 택배사마다 달라 검증하지 않는다 —
 *                   자릿수를 우리가 정해 두면 새 택배사가 들어올 때 등록이 막힌다
 */
public record ShipmentRequest(

		@Schema(description = "택배사 이름. 구매자에게 그대로 보인다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "CJ대한통운")
		@NotBlank(message = "택배사는 필수입니다")
		@Size(max = 50, message = "택배사는 50자를 넘을 수 없습니다")
		String carrier,

		@Schema(description = "스마트택배 택배사 코드. GET /seller/carriers 가 준 값이다. "
				+ "비우면 송장은 등록되지만 배송조회는 제공되지 않는다", example = "04")
		@Size(max = 10, message = "택배사 코드는 10자를 넘을 수 없습니다")
		String carrierCode,

		@Schema(description = "송장번호. 택배사마다 형식이 달라 자릿수를 검증하지 않는다",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "123456789012")
		@NotBlank(message = "송장번호는 필수입니다")
		@Size(max = 50, message = "송장번호는 50자를 넘을 수 없습니다")
		String trackingNo
) {
}
