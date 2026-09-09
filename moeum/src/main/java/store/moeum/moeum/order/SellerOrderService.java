package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.SellerOrderCounts;
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.SellerOrderDetailResponse;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.payment.domain.Payment;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 셀러 주문 목록 · 상세 (와이어프레임 G6).
 *
 * <b>소유권은 쿼리에 박아서 지킨다.</b> {@code where seller_id = :sellerId} 라서
 * 남의 주문이 결과에 섞일 길이 없다 — 꺼낸 뒤에 걸러 내는 방식은 한 군데만 빠뜨려도 샌다.
 *
 * 승인(APPROVED) 여부는 보지 않는다. 기존 셀러 조회들(findMine · findMineDetail)이
 * 전부 그렇다 — 승인 게이트는 판매 폼 '생성'에만 걸려 있고, 미승인 셀러는 애초에 폼이
 * 없어 주문도 없다.
 */
@Service
@RequiredArgsConstructor
public class SellerOrderService {

	/** 한 번에 내려주는 카드 수. 무한 스크롤이라 넉넉할 필요가 없다 */
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final OrderGroupRepository orderGroupRepository;
	private final PaymentRepository paymentRepository;
	private final ShippingRepository shippingRepository;
	private final SaleFormRepository saleFormRepository;
	private final SellerService sellerService;

	/**
	 * 목록. 탭 배지 숫자를 함께 내려준다.
	 *
	 * 응답 조립을 이 트랜잭션 안에서 끝낸다 — 밖에서 만들면 지연 로딩이 터진다.
	 */
	@Transactional(readOnly = true)
	public SellerOrderPageResponse list(String kakaoId, SellerOrderTab tab, Long saleFormId,
	                                    String q, int page, int size) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		requireOwnedForm(seller, saleFormId);

		String keyword = likePattern(q);
		String tabName = (tab == null) ? null : tab.name();

		Page<OrderGroup> groups = orderGroupRepository.findSellerOrders(
				seller.getId(), tabName, saleFormId, keyword,
				PageRequest.of(Math.max(page, 0), clampSize(size)));

		List<Long> ids = groups.getContent().stream().map(OrderGroup::getId).toList();
		Map<Long, Shipping> shippings = shippingsOf(ids);
		Map<Long, Map<PaymentPhase, Payment>> payments = paymentsOf(ids);

		List<SellerOrderPageResponse.SellerOrderItem> items = groups.getContent().stream()
				.map(group -> SellerOrderPageResponse.itemOf(group,
						shippings.get(group.getId()),
						paymentOf(payments, group.getId(), PaymentPhase.FIRST),
						paymentOf(payments, group.getId(), PaymentPhase.SECOND)))
				.toList();

		SellerOrderCounts counts =
				orderGroupRepository.countSellerOrderTabs(seller.getId(), saleFormId, keyword);

		return new SellerOrderPageResponse(
				SellerOrderPageResponse.countsOf(counts),
				items,
				new SellerOrderPageResponse.PageInfo(groups.getNumber(), groups.getSize(),
						groups.getTotalElements(), groups.getTotalPages(), groups.hasNext()));
	}

	/** 상세 드로어. 주문번호로 찾는다 — 셀러가 화면에서 보고 부르는 값이 그것이다 */
	@Transactional(readOnly = true)
	public SellerOrderDetailResponse detail(String kakaoId, String orderNo) {
		Seller seller = sellerService.getByKakaoId(kakaoId);

		OrderGroup group = orderGroupRepository.findByOrderNo(orderNo)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		// 남의 주문번호를 넣어 봤을 때 "있는데 권한 없음" 과 "없음" 을 구분해 주지 않는다
		if (!group.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND);
		}

		return SellerOrderDetailResponse.of(group,
				shippingRepository.findByOrderGroupId(group.getId()).orElse(null),
				paymentRepository.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.FIRST).orElse(null),
				paymentRepository.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.SECOND).orElse(null));
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 판매별 필터(G5-O)로 들어온 폼이 이 셀러 것인지 본다.
	 *
	 * 없는 폼과 남의 폼을 똑같이 404 로 돌려준다 — 403 이면 "그 id 에 폼이 있긴 하다" 가
	 * 새어 나가 id 를 훑어 남의 판매를 알아낼 수 있다.
	 */
	private void requireOwnedForm(Seller seller, Long saleFormId) {
		if (saleFormId == null) {
			return;
		}
		SaleForm form = saleFormRepository.findById(saleFormId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		if (!form.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
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

	/**
	 * 검색어를 LIKE 패턴으로 만든다.
	 *
	 * <b>{@code %} 와 {@code _} 를 escape 한다.</b> 그냥 끼워 넣으면 셀러가 친 {@code %} 가
	 * 와일드카드로 동작해서 주문 전체가 걸린다. 쿼리는 {@code escape '!'} 로 받는다.
	 */
	private static String likePattern(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String escaped = q.trim()
				.replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
	}

	private static int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
