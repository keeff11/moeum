package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
		@Schema(description = "이 주문(결제 세션)을 가리키는 토큰. 결제 시작·홀드 해제에 쓴다")
		String sessionToken,

		@Schema(description = "주문 묶음 상태")
		OrderGroupStatus status,

		@Schema(description = "재고 홀드가 풀리는 시각. 이때까지 결제하지 않으면 자리가 없어진다")
		LocalDateTime holdExpiresAt,

		@Schema(description = "홀드 만료까지 남은 초. 결제 화면 타이머가 이 값을 쓴다", example = "870")
		long remainingSeconds,

		@Schema(description = "1차금 상품 합계. 배송비는 들어 있지 않다. "
				+ "실제로 지금 결제할 금액은 firstPaymentAmount 를 쓴다", example = "60000")
		int deposit1Total,

		@Schema(description = "2차금 합계. 입고 후 청구될 잔금이다. 0 이면 2차금 단계가 없다는 뜻이고, "
				+ "그때는 배송비도 1차금에 실린다", example = "36000")
		int deposit2Total,

		@Schema(description = "배송비. 묶음당 1회다. 2차금이 있으면 그쪽에서, 없으면 1차금에서 청구된다",
				example = "3000")
		int shippingFee,

		@Schema(description = "★ 지금 결제창에 띄울 금액. 화면에서 직접 더하지 말고 이 값을 쓴다. "
				+ "2차금이 없는 묶음(단독 판매)은 배송비가 여기 포함돼 있다 (D-046)",
				example = "63000")
		int firstPaymentAmount,

		@Schema(description = "판매 폼별 주문. 폼마다 입고 시점이 달라 상태가 따로 돈다")
		List<OrderLine> orders
) {

	@Schema(description = "판매 폼 하나에 대한 주문. 부분 취소의 단위이기도 하다")
	public record OrderLine(

			@Schema(description = "주문 id. 폼 하나만 취소할 때 이 값을 보낸다", example = "44")
			Long orderId,

			@Schema(description = "판매 폼 id", example = "12")
			Long saleFormId,

			@Schema(description = "상품명", example = "아크릴 스탠드")
			String saleFormTitle,

			@Schema(description = "이 폼에서 주문한 총 수량", example = "2")
			int qty,

			@Schema(description = "이 폼의 1차금 합계", example = "40000")
			int deposit1Sum,

			@Schema(description = "이 폼의 2차금 합계", example = "24000")
			int deposit2Sum,

			@Schema(description = "선택한 옵션들")
			List<ItemLine> items
	) {
	}

	@Schema(description = "주문한 옵션 한 줄. 주문 시점의 이름·금액을 그대로 남긴다 — "
			+ "셀러가 나중에 옵션을 바꿔도 이미 산 주문의 내역은 변하지 않는다")
	public record ItemLine(

			@Schema(description = "옵션 id", example = "31")
			Long optionId,

			@Schema(description = "주문 당시의 상품명", example = "아크릴 스탠드")
			String productName,

			@Schema(description = "주문 당시의 옵션명", example = "블루")
			String optionName,

			@Schema(description = "수량", example = "2")
			int qty,

			@Schema(description = "주문 당시의 1차금(개당)", example = "20000")
			int deposit1Amount,

			@Schema(description = "주문 당시의 2차금(개당)", example = "12000")
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
				group.getDeposit1Total(), group.getDeposit2Total(), group.getShippingFee(),
				group.firstPaymentAmount(), lines);
	}
}
