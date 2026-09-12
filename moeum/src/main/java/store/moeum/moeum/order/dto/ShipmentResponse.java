package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.Shipping;

import java.time.LocalDateTime;

/** 송장 등록 결과 (D-047) */
@Schema(description = "송장 등록 결과")
public record ShipmentResponse(

		@Schema(description = "주문번호", example = "ORD-260912-41")
		String orderNo,

		@Schema(description = "등록된 택배사", example = "CJ대한통운")
		String carrier,

		@Schema(description = "등록된 송장번호", example = "123456789012")
		String trackingNo,

		@Schema(description = "발송 처리된 시각. 번호를 고쳐도 이 값은 처음 등록한 시각 그대로다")
		LocalDateTime shippedAt,

		@Schema(description = "이번 호출로 발송 완료가 된 것인가. false 면 송장번호만 고친 것이고 "
				+ "발송 알림도 다시 나가지 않는다", example = "true")
		boolean newlyShipped
) {

	public static ShipmentResponse of(OrderGroup group, Shipping shipping, boolean newlyShipped) {
		return new ShipmentResponse(group.getOrderNo(), shipping.getCarrier(),
				shipping.getTrackingNo(), shipping.getShippedAt(), newlyShipped);
	}
}
