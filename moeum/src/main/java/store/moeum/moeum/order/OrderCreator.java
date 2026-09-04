package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.ProductOptionRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.domain.Seller;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 재고 확보 + 주문 생성. <b>이 클래스 전체가 TX1 이다.</b>
 *
 * 재시도는 여기 걸지 않는다. {@link OrderService} 가 트랜잭션 바깥에서 감싼다 —
 * 같은 메서드에 @Transactional 과 @Retryable 을 같이 걸면 롤백된 트랜잭션 안에서 재시도하게 된다.
 *
 * 외부 API 호출은 한 줄도 없다. point3 세션 생성은 결제하기(/pay) 시점의 별도 요청이고,
 * 그래서 그쪽이 실패해도 여기서 잡은 홀드는 살아남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreator {

	/** 홀드 만료 15분 고정. 실패 지점별로 다르게 두지 않는다 (D-002) */
	private static final int HOLD_MINUTES = 15;
	private static final String SESSION_TOKEN_PREFIX = "cs_";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final SaleFormRepository saleFormRepository;
	private final ProductOptionRepository optionRepository;
	private final OrderGroupRepository orderGroupRepository;
	private final StockHoldRepository stockHoldRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public OrderGroupResponse create(Buyer buyer, OrderCreateRequest request) {
		Map<Long, Integer> qtyByOption = mergeQuantities(request);
		List<ProductOption> options = loadOptions(qtyByOption.keySet().stream().toList());
		Seller seller = singleSellerOf(options);

		// 판매 폼별로 묶는다. 재고 단위가 폼이라 같은 폼의 여러 옵션은 한 번에 확보해야 한다
		Map<SaleForm, List<ProductOption>> byForm = groupByForm(options);

		OrderGroup group = OrderGroup.create(newSessionToken(), buyer, seller, seller.getShippingFee());
		LocalDateTime expiresAt = LocalDateTime.now(KST).plusMinutes(HOLD_MINUTES);

		// ★ 판매 폼 id 오름차순. 두 구매자가 서로 반대 순서로 잠그면 데드락이 난다
		List<SaleForm> forms = byForm.keySet().stream()
				.sorted(Comparator.comparing(SaleForm::getId))
				.toList();

		for (SaleForm form : forms) {
			List<ProductOption> formOptions = byForm.get(form);
			int totalQty = formOptions.stream().mapToInt(option -> qtyByOption.get(option.getId())).sum();

			validatePerUserLimit(form, totalQty);
			acquire(form, totalQty);

			Order order = Order.create(form);
			for (ProductOption option : formOptions) {
				order.addItem(OrderItem.snapshotOf(option.getProduct(), option, qtyByOption.get(option.getId())));
			}
			group.addOrder(order);
		}

		validateMinOrderAmount(forms, group);
		orderGroupRepository.saveAndFlush(group);

		for (Order order : group.getOrders()) {
			stockHoldRepository.save(StockHold.held(order, order.getSaleForm(), order.getQty(), expiresAt));
		}
		stockHoldRepository.flush();

		// 응답도 이 트랜잭션 안에서 만든다. 밖에서 만들면 지연 로딩이 터진다
		return OrderGroupResponse.of(group, expiresAt);
	}

	/**
	 * ★ 재고 확보. 조건부 UPDATE 한 방이고, 영향 행 0이면 품절 또는 마감이다.
	 *
	 * 여기서 예외를 던지면 이미 확보한 앞선 폼들도 함께 롤백된다.
	 * 배송비가 묶음당 1회라 일부만 성공시키면 배송비를 나눌 방법이 없다 (D-009).
	 */
	private void acquire(SaleForm form, int qty) {
		int affected = saleFormRepository.hold(form.getId(), qty);

		if (affected == 0) {
			log.info("재고 확보 실패: saleFormId={}, qty={}", form.getId(), qty);
			throw new OutOfStockException(form.getId(),
					"'" + form.getTitle() + "' 의 재고가 부족하거나 판매가 마감되었습니다.");
		}
	}

	/** 같은 옵션이 여러 번 실려 오면 합친다. 그래야 폼 단위 확보 수량이 정확해진다 */
	private Map<Long, Integer> mergeQuantities(OrderCreateRequest request) {
		Map<Long, Integer> merged = new LinkedHashMap<>();
		for (OrderCreateRequest.Item item : request.items()) {
			merged.merge(item.optionId(), item.qty(), Integer::sum);
		}
		return merged;
	}

	private List<ProductOption> loadOptions(List<Long> optionIds) {
		List<ProductOption> options = optionRepository.findAllWithFormByIdIn(optionIds);
		if (options.size() != optionIds.size()) {
			throw new BusinessException(ErrorCode.OPTION_NOT_FOUND);
		}
		return options;
	}

	/** 한 묶음은 한 셀러다. 배송비가 셀러 단위 · 묶음당 1회라 섞이면 나눌 수 없다 */
	private Seller singleSellerOf(List<ProductOption> options) {
		List<Seller> sellers = options.stream()
				.map(option -> option.getProduct().getSaleForm().getSeller())
				.distinct()
				.toList();

		if (sellers.size() != 1) {
			throw new BusinessException(ErrorCode.MULTIPLE_SELLERS);
		}
		return sellers.get(0);
	}

	private Map<SaleForm, List<ProductOption>> groupByForm(List<ProductOption> options) {
		Map<SaleForm, List<ProductOption>> byForm = new LinkedHashMap<>();
		for (ProductOption option : options) {
			byForm.computeIfAbsent(option.getProduct().getSaleForm(), form -> new java.util.ArrayList<>())
					.add(option);
		}
		return byForm;
	}

	private void validatePerUserLimit(SaleForm form, int qty) {
		if (form.getMaxPerUser() != null && qty > form.getMaxPerUser()) {
			throw new BusinessException(ErrorCode.MAX_PER_USER_EXCEEDED,
					"'" + form.getTitle() + "' 은 1인당 " + form.getMaxPerUser() + "개까지 구매할 수 있습니다.");
		}
	}

	/** 최소 주문 금액은 폼별 설정이라 폼별 1차금 합계로 본다 */
	private void validateMinOrderAmount(List<SaleForm> forms, OrderGroup group) {
		for (SaleForm form : forms) {
			int deposit1Sum = group.getOrders().stream()
					.filter(order -> order.getSaleForm().getId().equals(form.getId()))
					.mapToInt(Order::getDeposit1Sum)
					.sum();

			if (deposit1Sum < form.getMinOrderAmount()) {
				throw new BusinessException(ErrorCode.MIN_ORDER_AMOUNT_NOT_MET,
						"'" + form.getTitle() + "' 은 " + form.getMinOrderAmount() + "원부터 주문할 수 있습니다.");
			}
		}
	}

	private String newSessionToken() {
		byte[] bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return SESSION_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
