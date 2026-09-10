package store.moeum.moeum.seller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.SellerOrderCounts;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.order.dto.SellerOrderPageResponse.SellerOrderItem;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.dto.SellerHomeResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 셀러 홈 (와이어프레임 G1).
 *
 * <b>세는 일을 새로 만들지 않는다.</b> 처리할 주문은 G6 의 탭 배지 집계
 * ({@code countSellerOrderTabs}), 최근 주문은 G6 목록의 첫 장을 그대로 쓴다.
 * 홈이 자기 방식으로 세면 목록과 숫자가 갈라지고, 그때 셀러는 어느 쪽을 믿을지 모른다 —
 * S10 청구 대상이 걸려 있는 숫자라 갈라지면 돈 문제가 된다 (D-035).
 *
 * <b>소유권은 쿼리에 박혀 있다.</b> 세 조회 전부 {@code where seller_id = :sellerId} 라
 * 남의 것이 섞일 길이 없다 — {@link SellerOrderService} 와 같은 방식이다.
 */
@Service
@RequiredArgsConstructor
public class SellerHomeService {

	/** 홈에 세워 두는 판매 카드 수. 더 보려면 G5 목록으로 간다 */
	private static final int ACTIVE_SALE_LIMIT = 6;

	/** 최근 주문 줄 수. 화면이 한 화면에 담기는 만큼만이다 */
	private static final int RECENT_ORDER_SIZE = 5;

	private final OrderGroupRepository orderGroupRepository;
	private final SaleFormRepository saleFormRepository;
	private final PaymentRepository paymentRepository;
	private final ShippingRepository shippingRepository;
	private final SellerService sellerService;

	/**
	 * 홈 한 장을 통째로 만든다.
	 *
	 * 응답 조립을 이 트랜잭션 안에서 끝낸다 — 밖에서 만들면 지연 로딩이 터진다.
	 */
	@Transactional(readOnly = true)
	public SellerHomeResponse home(String kakaoId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		LocalDateTime now = LocalDateTime.now(KST);

		SellerOrderCounts counts =
				orderGroupRepository.countSellerOrderTabs(seller.getId(), null, null);

		List<SellerHomeResponse.ActiveSale> activeSales =
				saleFormRepository.findSellerActiveForms(seller.getId(), ACTIVE_SALE_LIMIT).stream()
						.map(form -> SellerHomeResponse.saleOf(form, now))
						.toList();

		return new SellerHomeResponse(
				seller.getStoreName(),
				SellerHomeResponse.todoOf(counts),
				activeSales,
				recentOrders(seller),
				now);
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 최근 주문. 탭·검색 없이 G6 목록의 첫 장이다.
	 *
	 * 카드를 {@link SellerOrderPageResponse#itemOf} 로 만드는 이유는 프론트가 홈과 목록에서
	 * 같은 컴포넌트를 쓰게 하기 위해서다. 모양이 갈라지면 "외 N건" 이나 결제 문구 같은 규칙을
	 * 두 군데서 따로 손보게 된다.
	 */
	private List<SellerOrderItem> recentOrders(Seller seller) {
		Page<OrderGroup> groups = orderGroupRepository.findSellerOrders(
				seller.getId(), null, null, null, PageRequest.of(0, RECENT_ORDER_SIZE));

		List<Long> ids = groups.getContent().stream().map(OrderGroup::getId).toList();
		Map<Long, Shipping> shippings = shippingsOf(ids);
		Map<Long, Map<PaymentPhase, Payment>> payments = paymentsOf(ids);

		return groups.getContent().stream()
				.map(group -> SellerOrderPageResponse.itemOf(group,
						shippings.get(group.getId()),
						paymentOf(payments, group.getId(), PaymentPhase.FIRST),
						paymentOf(payments, group.getId(), PaymentPhase.SECOND)))
				.toList();
	}

	private Map<Long, Shipping> shippingsOf(List<Long> orderGroupIds) {
		if (orderGroupIds.isEmpty()) {
			return Map.of();
		}
		return shippingRepository.findByOrderGroupIdIn(orderGroupIds).stream()
				.collect(Collectors.toMap(s -> s.getOrderGroup().getId(), Function.identity()));
	}

	/** 묶음당 최대 2행(차수)이라 묶음 id → 차수 → 결제 로 접는다 */
	private Map<Long, Map<PaymentPhase, Payment>> paymentsOf(List<Long> orderGroupIds) {
		if (orderGroupIds.isEmpty()) {
			return Map.of();
		}
		return paymentRepository.findByOrderGroupIdIn(orderGroupIds).stream()
				.collect(Collectors.groupingBy(p -> p.getOrderGroup().getId(),
						Collectors.toMap(Payment::getPhase, Function.identity())));
	}

	private Payment paymentOf(Map<Long, Map<PaymentPhase, Payment>> payments,
	                          Long orderGroupId, PaymentPhase phase) {
		return payments.getOrDefault(orderGroupId, Map.of()).get(phase);
	}
}
