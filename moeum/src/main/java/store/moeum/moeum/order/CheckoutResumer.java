package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.order.domain.HoldStatus;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 진행 중인 체크아웃 세션 이어받기 (D-015 · D-037).
 *
 * <b>로그인 왕복에서 돌아온 뒤 자동 호출이 중복되면 홀드가 두 벌 잡힌다.</b>
 * 비로그인 상태에서는 홀드를 잡지 않기로 했으므로(D-015), 복귀 후 프론트가 선택을
 * 복원해 {@code POST /checkout-sessions} 를 자동으로 부른다. 그 호출이 두 번 들어오면
 * 같은 구매자가 같은 상품의 재고를 두 번 잠근다 — 선착순 공동구매에서는 그만큼 손해다.
 *
 * <b>{@link OrderService} 와 별도 빈인 이유는 트랜잭션 경계다.</b> {@code place} 는
 * {@code @Retryable} 이 트랜잭션 <em>바깥</em>에 있어야 해서 일부러 트랜잭션이 없다.
 * 같은 클래스 안에 두면 자기 호출이라 {@code @Transactional} 이 붙지 않고,
 * 지연 로딩된 주문 항목을 읽는 순간 터진다. {@link OrderCreator} 를 나눈 것과 같은 이유다.
 */
@Component
@RequiredArgsConstructor
public class CheckoutResumer {

	private final OrderGroupRepository orderGroupRepository;
	private final StockHoldRepository stockHoldRepository;

	/**
	 * 항목이 <b>정확히 같은</b> 진행 중 세션이 있으면 그것을 돌려준다.
	 *
	 * <b>항목이 다르면 이어받지 않는다.</b> 사용자가 방금 고른 것과 다른 것을 결제하게 할 수는
	 * 없다. 그때는 새 세션이 생기고 이전 홀드는 15분 뒤 만료 배치가 걷어 간다 — 그 사이
	 * 재고가 이중으로 묶이는 것은 지금 받아들이는 비용이다 (D-037).
	 */
	@Transactional(readOnly = true)
	public Optional<OrderGroupResponse> resume(Buyer buyer, OrderCreateRequest request) {
		Map<Long, Integer> wanted = mergeQuantities(request);
		LocalDateTime now = LocalDateTime.now(KST);

		return orderGroupRepository.findActiveByBuyer(buyer.getId()).stream()
				.filter(group -> wanted.equals(selectionOf(group)))
				.filter(group -> holdsAliveAt(group, now))
				.findFirst()
				.map(this::toResponse);
	}

	// ---------------------------------------------------------------- 내부

	/** 이 묶음이 담고 있는 옵션별 수량. 요청과 같은 모양으로 만들어 통째로 비교한다 */
	private Map<Long, Integer> selectionOf(OrderGroup group) {
		Map<Long, Integer> selection = new LinkedHashMap<>();

		for (Order order : group.activeOrders()) {
			for (OrderItem item : order.getItems()) {
				selection.merge(item.getOption().getId(), item.getQty(), Integer::sum);
			}
		}
		return selection;
	}

	/**
	 * 홀드가 아직 살아 있는가.
	 *
	 * <b>만료 배치가 아직 안 돌았을 뿐인 세션을 돌려주면 안 된다.</b> 구매자는 남은 시간이
	 * 0인 화면을 받고 결제 단계에서 홀드 검증에 튕긴다. 그럴 바에는 새로 잡는 것이 낫다.
	 */
	private boolean holdsAliveAt(OrderGroup group, LocalDateTime now) {
		List<Long> orderIds = group.activeOrders().stream().map(Order::getId).toList();
		if (orderIds.isEmpty()) {
			return false;
		}
		List<StockHold> holds = stockHoldRepository.findByOrderIdIn(orderIds);

		return holds.size() == orderIds.size()
				&& holds.stream().allMatch(hold -> hold.getStatus() == HoldStatus.HELD
						&& hold.getExpiresAt().isAfter(now));
	}

	/** OrderCreator 와 같은 규칙 — 같은 옵션이 여러 번 오면 합친다 */
	private static Map<Long, Integer> mergeQuantities(OrderCreateRequest request) {
		Map<Long, Integer> merged = new LinkedHashMap<>();
		for (OrderCreateRequest.Item item : request.items()) {
			merged.merge(item.optionId(), item.qty(), Integer::sum);
		}
		return merged;
	}

	/** 남은 시간은 가장 먼저 끝나는 홀드를 기준으로 한다 */
	private OrderGroupResponse toResponse(OrderGroup group) {
		List<Long> orderIds = group.getOrders().stream().map(Order::getId).toList();

		LocalDateTime expiresAt = stockHoldRepository.findByOrderIdIn(orderIds).stream()
				.filter(hold -> hold.getStatus() == HoldStatus.HELD)
				.map(StockHold::getExpiresAt)
				.min(LocalDateTime::compareTo)
				.orElse(group.getCreatedAt());

		return OrderGroupResponse.of(group, expiresAt);
	}
}
