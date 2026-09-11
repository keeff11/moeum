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
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.payment.refund.RefundPolicy;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;

import java.util.List;

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
	private final ImageStorage imageStorage;

	/**
	 * 목록. 응답 조립을 이 트랜잭션 안에서 끝낸다 —
	 * 제목·썸네일·취소 가능 여부가 전부 폼을 타고 들어가는 지연 로딩이다.
	 */
	@Transactional(readOnly = true)
	public BuyerOrderPageResponse list(String kakaoId, SaleType saleType, int page, int size) {
		Page<OrderGroup> groups = orderGroupRepository.findBuyerOrders(
				kakaoId, saleType, PageRequest.of(Math.max(page, 0), clampSize(size)));

		List<BuyerOrderPageResponse.BuyerOrderItem> items = groups.getContent().stream()
				.map(group -> BuyerOrderPageResponse.itemOf(
						group, thumbnailOf(group), cancelableOf(group)))
				.toList();

		return new BuyerOrderPageResponse(items,
				new BuyerOrderPageResponse.PageInfo(groups.getNumber(), groups.getSize(),
						groups.getTotalElements(), groups.getTotalPages(), groups.hasNext()));
	}

	// ---------------------------------------------------------------- 내부

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
