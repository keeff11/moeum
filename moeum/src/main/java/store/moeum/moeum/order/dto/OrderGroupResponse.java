package store.moeum.moeum.order.dto;

import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 홀드 결과. 타이머(holdExpiresAt)는 B4 배송지 화면부터 표시해야 한다 —
 * 홀드가 배송지 입력 전에 시작되므로, B5 에서만 띄우면 이미 흘러간 시간을 알 수 없다.
 */
public record OrderGroupResponse(
		String sessionToken,
		OrderGroupStatus status,
		LocalDateTime holdExpiresAt,
		long remainingSeconds,
		int deposit1Total,
		int deposit2Total,
		int shippingFee,
		List<OrderLine> orders
) {

	public record OrderLine(
			Long orderId,
			Long saleFormId,
			String saleFormTitle,
			int qty,
			int deposit1Sum,
			int deposit2Sum,
			List<ItemLine> items
	) {
	}

	public record ItemLine(
			Long optionId,
			String productName,
			String optionName,
			int qty,
			int deposit1Amount,
			int deposit2Amount
	) {
	}

	public static OrderGroupResponse of(OrderGroup group, LocalDateTime holdExpiresAt) {
		long remaining = Math.max(0, Duration.between(LocalDateTime.now(KST), holdExpiresAt).toSeconds());

		List<OrderLine> lines = group.getOrders().stream()
				.map(order -> new OrderLine(
						order.getId(),
						order.getSaleForm().getId(),
						order.getSaleForm().getTitle(),
						order.getQty(),
						order.getDeposit1Sum(),
						order.getDeposit2Sum(),
						order.getItems().stream()
								.map(item -> new ItemLine(
										item.getOption().getId(),
										item.getProductName(),
										item.getOptionName(),
										item.getQty(),
										item.getDeposit1Amount(),
										item.getDeposit2Amount()))
								.toList()))
				.toList();

		return new OrderGroupResponse(
				group.getSessionToken(), group.getStatus(), holdExpiresAt, remaining,
				group.getDeposit1Total(), group.getDeposit2Total(), group.getShippingFee(), lines);
	}
}
