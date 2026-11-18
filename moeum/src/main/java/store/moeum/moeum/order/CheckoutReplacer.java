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
import store.moeum.moeum.order.domain.OrderGroupStatus;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.payment.domain.PaymentRepository;

import java.util.Collection;
import java.util.List;

/**
 * 같은 구매자가 같은 판매 폼에 홀드를 두 벌 갖지 않게 한다 (D-073).
 *
 * <b>새로 담으면 이전 것을 풀고 새 것을 잡는다.</b> 막지 않는 이유는 뒤로 가서 수량을 바꾸는
 * 정상 사용자 때문이다. 뒤로 갈 때의 이탈 해제는 브라우저 종료 · 앱 전환이면 오지 않아서,
 * 막으면 그 사람은 최대 30분 동안 같은 폼을 못 산다.
 *
 * <b>결제창까지 간 묶음은 풀지 않고 막는다.</b> point3 쪽에서 결제가 진행 중일 수 있고,
 * 그 재고를 풀면 승인이 와도 확정할 재고가 없다. payment 행이 생긴 시점부터가 그 구간이다 —
 * 묶음 상태는 세션이 붙어야(attachSession) PAY_PENDING 으로 바뀌어서, 그 직전까지는
 * CREATED 인 채로 결제창이 열리고 있다.
 *
 * <b>이 클래스는 트랜잭션을 열지 않는다.</b> 주문 생성(TX1) 안에서 돈다. 그래서 새 홀드 확보가
 * 품절로 실패하면 여기서 푼 것도 같이 롤백되어, 구매자는 원래 잡고 있던 자리를 잃지 않는다.
 * 같은 이유로 수량을 1 → 2 로 바꿀 때 자기가 잡고 있던 1개는 다시 쓸 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutReplacer {

	private final OrderGroupRepository orderGroupRepository;
	private final StockHoldRepository stockHoldRepository;
	private final PaymentRepository paymentRepository;
	private final StockLedger stockLedger;

	/**
	 * 이 구매자가 {@code formIds} 중 하나라도 잡고 있는 묶음을 통째로 푼다.
	 *
	 * 겹치는 폼만이 아니라 묶음 전체를 푸는 이유는 배송비다. 묶음당 1회라 일부만 남기면
	 * 남은 묶음의 배송비를 다시 계산할 방법이 없다 (D-009 와 같은 이유).
	 *
	 * @throws BusinessException 결제창까지 간 묶음이 있으면 {@link ErrorCode#CHECKOUT_PAYMENT_IN_PROGRESS}
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void releasePrevious(Buyer buyer, Collection<Long> formIds) {
		if (formIds.isEmpty()) {
			return;
		}
		// id 만 먼저 읽는다. 묶음을 이 쿼리로 올려 두면 아래 잠금 조회가 캐시된 상태를 돌려준다
		List<Long> groupIds = orderGroupRepository.findIdsHoldingForms(buyer.getId(), formIds);

		for (Long groupId : groupIds) {
			// 잠그고 다시 본다. 다른 탭이 같은 묶음을 동시에 교체하거나 결제를 확정하는 중일 수 있다
			OrderGroup group = orderGroupRepository.findByIdForUpdate(groupId).orElse(null);
			if (group == null) {
				continue;
			}
			if (!isReplaceable(group)) {
				log.info("결제 진행 중인 묶음이 있어 새로 담지 않는다: buyerId={}, orderGroupId={}, status={}",
						buyer.getId(), group.getId(), group.getStatus());
				throw new BusinessException(ErrorCode.CHECKOUT_PAYMENT_IN_PROGRESS);
			}
			release(group);
		}
	}

	/** 아직 결제창을 연 적이 없는 묶음만 갈아 끼울 수 있다 */
	private boolean isReplaceable(OrderGroup group) {
		return group.getStatus() == OrderGroupStatus.CREATED
				&& paymentRepository.findByOrderGroupIdAndPhase(group.getId(), PaymentPhase.FIRST).isEmpty();
	}

	/**
	 * 홀드를 잠그고 푼다. 만료 배치는 SKIP LOCKED 라 겹치면 한쪽만 처리하고,
	 * 이미 RELEASED 면 {@link StockHold#release()} 가 false 라 재고가 두 번 돌아가지 않는다.
	 */
	private void release(OrderGroup group) {
		List<Long> orderIds = group.getOrders().stream().map(Order::getId).toList();
		if (orderIds.isEmpty()) {
			return;
		}
		int released = 0;
		for (StockHold hold : stockHoldRepository.findByOrderIdInForUpdate(orderIds)) {
			if (!hold.release()) {
				continue;
			}
			if (stockLedger.release(hold) == 0) {
				log.warn("교체 중 홀드 해제 시 재고 반환 실패: holdId={}, saleFormId={}, qty={}",
						hold.getId(), hold.getSaleForm().getId(), hold.getQty());
			}
			released++;
		}
		if (released > 0) {
			group.expire();
			log.info("새로 담아 이전 홀드를 풀었다: orderGroupId={}, holds={}", group.getId(), released);
		}
	}
}
