package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.payment.domain.GroupAmount;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.payment.refund.RefundPolicy;
import store.moeum.moeum.payment.refund.RefundRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 구매자 주문 목록 (와이어프레임 B13).
 *
 * <b>소유권은 쿼리에 박아서 지킨다.</b> {@code where g.buyer.kakaoId = :kakaoId} 라
 * 남의 주문이 결과에 섞일 길이 없다 — 여기 섞이면 {@code orderToken} 을 통째로
 * 넘겨주는 것이고, 토큰만 있으면 상태 조회와 취소가 된다.
 */
@Service
@RequiredArgsConstructor
public class BuyerOrderService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final OrderGroupRepository orderGroupRepository;
	private final ShippingRepository shippingRepository;
	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;
	private final ImageStorage imageStorage;

	/**
	 * 목록. 응답 조립을 이 트랜잭션 안에서 끝낸다 —
	 * 제목·썸네일·취소 가능 여부가 전부 폼을 타고 들어가는 지연 로딩이다.
	 */
	@Transactional(readOnly = true)
	public BuyerOrderPageResponse list(String kakaoId, SaleType saleType, int page, int size) {
		Page<OrderGroup> groups = orderGroupRepository.findBuyerOrders(
				kakaoId, saleType, PageRequest.of(Math.max(page, 0), clampSize(size)));

		List<Long> ids = groups.getContent().stream().map(OrderGroup::getId).toList();

		// 송장·결제·취소를 전부 한 번에 끌어온다 — 카드마다 조회하면 목록 한 장에 쿼리가 20번씩 더 나간다
		Map<Long, Shipping> shippings = shippingsOf(ids);
		Map<Long, Long> captured = sumsOf(ids, paymentRepository::sumCapturedByOrderGroupIdIn);
		Map<Long, Long> refunded = sumsOf(ids, refundRepository::sumRefundedByOrderGroupIdIn);

		List<BuyerOrderPageResponse.BuyerOrderItem> items = groups.getContent().stream()
				.map(group -> BuyerOrderPageResponse.itemOf(
						group, thumbnailOf(group), cancelableOf(group),
						shippings.get(group.getId()),
						paidAmountOf(captured, refunded, group.getId()),
						amountOf(refunded, group.getId())))
				.toList();

		return new BuyerOrderPageResponse(items,
				new BuyerOrderPageResponse.PageInfo(groups.getNumber(), groups.getSize(),
						groups.getTotalElements(), groups.getTotalPages(), groups.hasNext()));
	}

	// ---------------------------------------------------------------- 내부

	/** 송장이 없는 묶음은 키가 아예 없다 — 등록 전이라는 뜻이다 */
	private Map<Long, Shipping> shippingsOf(List<Long> orderGroupIds) {
		if (orderGroupIds.isEmpty()) {
			return Map.of();
		}
		return shippingRepository.findByOrderGroupIdIn(orderGroupIds)
				.stream()
				.collect(Collectors.toMap(s -> s.getOrderGroup().getId(), Function.identity()));
	}

	/**
	 * 지금까지 실제로 낸 금액 (B13).
	 *
	 * <b>출금이 확정된 결제에서 돌려준 금액을 뺀다.</b> 화면이 지금까지 총액만 보여 주고 있어
	 * 1차금만 낸 주문과 완납한 주문이 같은 숫자로 보였다 — 구매자가 잔금을 낸 줄 안다.
	 *
	 * <b>음수로 내려가지 않게 막는다.</b> 환불 세금 안분에서 원 단위가 위로 떨어지거나
	 * 정산 후 직접 이체건이 섞이면 뺀 값이 원금을 넘을 수 있는데, "-500원 냈다" 는 화면에
	 * 찍힐 값이 아니다. 그런 건은 0 으로 보여 주고 실제 정산은 결제 내역이 맡는다.
	 */
	private static int paidAmountOf(Map<Long, Long> captured, Map<Long, Long> refunded, Long groupId) {
		long paid = captured.getOrDefault(groupId, 0L) - refunded.getOrDefault(groupId, 0L);
		return Math.toIntExact(Math.max(paid, 0L));
	}

	private static int amountOf(Map<Long, Long> sums, Long groupId) {
		return Math.toIntExact(sums.getOrDefault(groupId, 0L));
	}

	/**
	 * 집계 결과를 묶음 id 로 접는다.
	 *
	 * <b>결제도 취소도 없는 묶음은 키가 아예 없다</b> — {@code group by} 는 행이 있어야
	 * 줄을 준다. 부르는 쪽이 전부 {@code getOrDefault(0)} 으로 읽는 이유다.
	 */
	private static Map<Long, Long> sumsOf(List<Long> orderGroupIds,
	                                      Function<List<Long>, List<GroupAmount>> query) {
		if (orderGroupIds.isEmpty()) {
			// 빈 목록으로 부르면 `in ()` 이 나간다. 애초에 셀 것이 없다
			return Map.of();
		}
		return query.apply(orderGroupIds).stream()
				.collect(Collectors.toMap(GroupAmount::orderGroupId, GroupAmount::amount));
	}

	/**
	 * 카드에 찍히는 취소 가능 여부.
	 *
	 * <b>{@code RefundPolicy} 만 본다 — point3 에 묻지 않는다.</b> 목록 한 장이 20건인데
	 * 건마다 외부 호출을 하면 화면 한 번에 20번 나가고, 그 사이 PG 가 느리면 목록 전체가 멈춘다.
	 * 여기는 버튼을 보여 줄지 정하는 값이고, <b>확정 판단은 누른 뒤
	 * {@code GET /orders/{token}/refundable} 이 한다.</b>
	 *
	 * 살아 있는 주문이 <b>전부</b> 취소 가능해야 참이다. 하나라도 막혀 있으면 "취소 가능" 을
	 * 보여 줬다가 눌렀을 때 거절되는 편이 나쁘다.
	 */
	private boolean cancelableOf(OrderGroup group) {
		List<Order> alive = group.activeOrders();
		return !alive.isEmpty() && alive.stream().allMatch(RefundPolicy::cancelable);
	}

	/** 첫 번째 이미지가 카드 썸네일이다. 없으면 null 로 두고 프론트가 자리표시자를 넣는다 */
	private String thumbnailOf(OrderGroup group) {
		return group.activeOrders().stream()
				.findFirst()
				.or(() -> group.getOrders().stream().findFirst())
				.map(Order::getSaleForm)
				.map(SaleForm::imageKeys)
				.flatMap(keys -> keys.stream().findFirst())
				.map(imageStorage::publicUrl)
				.orElse(null);
	}

	private static int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
