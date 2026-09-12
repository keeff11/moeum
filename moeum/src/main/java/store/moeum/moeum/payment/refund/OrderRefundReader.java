package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.domain.PaymentStatus;
import store.moeum.moeum.payment.refund.dto.RefundableResponse;

import java.util.List;
import java.util.Optional;

/**
 * 취소의 읽기 전용 판정. <b>여기서 쓰기도 point3 호출도 하지 않는다.</b>
 *
 * {@link OrderRefundService} 가 트랜잭션 밖에서 오케스트레이션하므로 읽기는 이 컴포넌트를 거친다 —
 * 같은 빈 안에서 부르면 프록시를 타지 않아 {@code @Transactional} 이 걸리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OrderRefundReader {

	private final OrderGroupRepository orderGroupRepository;
	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	/**
	 * 취소 계획을 세운다. 여기서 통과하지 못하면 point3 로 아무것도 나가지 않는다.
	 *
	 * @param orderId 폼 하나만 취소하면 그 주문 id, 남은 폼 전부면 null
	 */
	@Transactional(readOnly = true)
	public RefundPlan plan(String kakaoId, String orderToken, Long orderId) {
		OrderGroup group = requireOwnedGroup(kakaoId, orderToken);

		List<Order> active = group.activeOrders();
		if (active.isEmpty()) {
			throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED, "취소할 주문이 남아 있지 않습니다.");
		}

		List<Order> targets = targets(active, orderId);
		for (Order order : targets) {
			String reason = RefundPolicy.blockReason(order);
			if (reason != null) {
				throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED, reason);
			}
		}
		return build(group, active, targets, orderId);
	}

	/**
	 * 시스템이 거는 취소의 계획 — 목표수량 미달 등 (D-026).
	 *
	 * <b>소유권도 취소 구간도 보지 않는다.</b> 구매자 잘못이 아니라 폼이 성립하지 않은 것이라
	 * 발주가 나갔든 아니든 돌려줘야 한다.
	 *
	 * 계획을 세울 수 없으면 비어 있는 값을 준다 — 배치가 폼 하나 때문에 멈추면 안 된다.
	 */
	@Transactional(readOnly = true)
	public Optional<RefundPlan> systemPlan(Long orderId) {
		Order order = orderRepository.findById(orderId).orElse(null);
		if (order == null || order.isCanceled()) {
			return Optional.empty();
		}
		OrderGroup group = order.getOrderGroup();
		try {
			return Optional.of(build(group, group.activeOrders(), List.of(order), orderId));
		} catch (BusinessException e) {
			return Optional.empty();
		}
	}

	// ---------------------------------------------------------------- 계획 조립

	private RefundPlan build(OrderGroup group, List<Order> active, List<Order> targets, Long orderId) {
		// 남은 폼을 전부 취소하는가 — 배송비 환불 여부가 이 한 줄에 달렸다
		boolean fullGroup = targets.size() == active.size();

		Payment first = capturedPayment(group.getId(), PaymentPhase.FIRST)
				.orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_ALLOWED,
						"결제가 완료된 주문만 취소할 수 있습니다."));
		Payment second = capturedPayment(group.getId(), PaymentPhase.SECOND).orElse(null);

		int shipping = shippingRefund(group, fullGroup);
		boolean shippingOnFirst = !group.hasSecondPayment();

		int firstAmount = targets.stream().mapToInt(Order::getDeposit1Sum).sum()
				+ (shippingOnFirst ? shipping : 0);

		// 2차금이 아직 청구되지 않았으면 배송비도 받은 적이 없다. 여기서 더하면 과다 환불이다
		int secondAmount = second == null ? 0
				: targets.stream().mapToInt(Order::getDeposit2Sum).sum()
						+ (shippingOnFirst ? 0 : shipping);

		if (firstAmount == 0 && secondAmount == 0) {
			throw new BusinessException(ErrorCode.REFUND_NOT_ALLOWED, "취소할 금액이 없습니다.");
		}

		return new RefundPlan(group.getId(), group.getOrderToken(), orderId, fullGroup,
				first.getId(), firstAmount,
				second == null ? null : second.getId(), secondAmount);
	}

	/** 취소 화면용 조회. EOB 는 서비스가 시계를 보고 덧붙인다 */
	@Transactional(readOnly = true)
	public RefundableResponse view(String kakaoId, String orderToken) {
		OrderGroup group = requireOwnedGroup(kakaoId, orderToken);

		boolean secondCaptured = capturedPayment(group.getId(), PaymentPhase.SECOND).isPresent();
		List<Order> active = group.activeOrders();

		List<RefundableResponse.Item> items = active.stream()
				.map(order -> item(order, secondCaptured))
				.toList();

		boolean allCancelable = !items.isEmpty() && items.stream().allMatch(RefundableResponse.Item::refundable);
		boolean anyCancelable = items.stream().anyMatch(RefundableResponse.Item::refundable);

		// 배송비가 이미 청구됐는가. 2차금이 없는 묶음은 1차금에서 받았으므로
		// 2차금 결제를 볼 것이 아니다 (D-046). 안내가 실제 환불액과 어긋나면 안 된다
		boolean shippingCharged = !group.hasSecondPayment() || secondCaptured;

		return new RefundableResponse(orderToken, anyCancelable,
				anyCancelable ? null : "취소할 수 있는 주문이 없습니다.",
				null,
				group.getShippingFee(),
				// 배송비는 남은 폼을 전부 취소할 때만 함께 돌아간다
				allCancelable && shippingCharged && group.getShippingFee() > 0,
				items);
	}

	// ---------------------------------------------------------------- 내부

	private static RefundableResponse.Item item(Order order, boolean secondCaptured) {
		String blocked = RefundPolicy.blockReason(order);
		int second = secondCaptured ? order.getDeposit2Sum() : 0;

		return new RefundableResponse.Item(order.getId(), order.getSaleForm().getTitle(), order.getQty(),
				blocked == null, blocked,
				order.getDeposit1Sum(), second, order.getDeposit1Sum() + second);
	}

	/**
	 * 이번 취소에서 돌려줄 배송비. 돌려줄 것이 없으면 0.
	 *
	 * <b>배송비는 묶음당 1회다.</b> 폼 하나만 빠져도 나머지는 그대로 배송되므로 돌려주지 않는다 —
	 * 남은 폼을 전부 취소할 때만 함께 돌아간다.
	 *
	 * 어느 결제에서 뺄지는 {@code hasSecondPayment()} 가 정한다. <b>청구를 가른 값과 같은 값이다</b>
	 * (D-046 — 2차금이 없는 묶음은 배송비를 1차금에서 받는다). 여기가 청구와 어긋나면
	 * 받은 배송비를 돌려주지 않거나, 받은 적 없는 배송비를 돌려주게 된다.
	 */
	private static int shippingRefund(OrderGroup group, boolean fullGroup) {
		return fullGroup ? group.getShippingFee() : 0;
	}

	private static List<Order> targets(List<Order> active, Long orderId) {
		if (orderId == null) {
			return active;
		}
		List<Order> picked = active.stream().filter(o -> orderId.equals(o.getId())).toList();
		if (picked.isEmpty()) {
			// 남의 주문 id 를 넣어도 여기서 걸린다 — 묶음 소유권은 이미 확인했다
			throw new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND);
		}
		return picked;
	}

	private Optional<Payment> capturedPayment(Long groupId, PaymentPhase phase) {
		return paymentRepository.findByOrderGroupIdAndPhase(groupId, phase)
				.filter(payment -> payment.getStatus() == PaymentStatus.CAPTURED);
	}

	private OrderGroup requireOwnedGroup(String kakaoId, String orderToken) {
		OrderGroup group = orderGroupRepository.findByOrderToken(orderToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (!group.getBuyer().getKakaoId().equals(kakaoId)) {
			// 토큰을 알아도 남의 주문은 취소할 수 없다
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return group;
	}
}
