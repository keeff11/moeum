package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.outbox.OutboxRecorder;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.saleform.domain.SaleFormRepository;

import java.util.List;
import java.util.Map;

/**
 * 마감 시각이 지난 공구를 CLOSED 로 넘긴다. 1분마다 돈다 (payment-flow 7절).
 *
 * <b>여기서는 상태 전이만 한다.</b> 목표수량 미달 시 shortfall_policy 적용은
 * {@code ShortfallCancelBatch} 가 CLOSED 가 된 폼을 다시 훑어 처리한다 (D-026) —
 * 미달 취소는 point3 를 부르므로 이 배치의 트랜잭션 안에서 할 수 없다.
 * EXTEND 의 연장 횟수 규칙은 여전히 기획 미확정이다 (domain.md).
 *
 * 상태 전이만으로도 이 배치는 필요하다. 이게 없으면 마감 시각이 지나도 status 가
 * SELLING 으로 남아 구매자 화면에 구매 버튼이 계속 켜진다.
 *
 * 재고 확보 쿼리는 closes_at 을 직접 보므로 이 배치가 늦어도 초과 판매는 나지 않는다.
 * 여기는 화면에 보이는 상태를 맞추는 일이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaleFormCloseBatch {

	private final SaleFormRepository saleFormRepository;
	private final OrderRepository orderRepository;
	private final OutboxRecorder outboxRecorder;

	@Transactional
	@Scheduled(fixedDelayString = "${moeum.batch.sale-form-close-delay:60000}")
	public void run() {
		int closed = closeOnce();
		if (closed > 0) {
			log.info("마감 처리: {}건", closed);
		}
	}

	/**
	 * 한 번의 마감. 테스트가 직접 부를 수 있게 열어 둔다.
	 *
	 * <b>폼만 마감하면 주문은 모집 중에 머문다</b> (D-049). 구매자 화면의 진행 배지가
	 * {@code orders.status} 를 보므로, 폼이 CLOSED 인데 주문이 RECRUITING 이면
	 * 마감된 공구가 계속 "모집 중" 으로 보인다.
	 *
	 * 주문은 한 문장으로 같이 넘긴다 — 폼이 수백 개 마감돼도 엔티티를 올리지 않는다.
	 */
	@Transactional
	public int closeOnce() {
		// UPDATE 전에 대상을 집는다. 벌크 UPDATE 는 어느 폼이 마감됐는지 알려주지 않고,
		// 마감 알림(알림톡 4번)을 보내려면 대상을 알아야 한다 (D-050)
		List<Long> expiring = saleFormRepository.findExpiredSellingIds();

		int closed = saleFormRepository.closeExpired();
		if (closed == 0) {
			return 0;
		}

		int orders = orderRepository.closeRecruitingOfClosedForms();
		log.info("모집 마감: 폼 {}건, 주문 {}건", closed, orders);

		for (Long saleFormId : expiring) {
			notifyClosed(saleFormId);
		}
		return closed;
	}

	/**
	 * 마감을 구매자에게 알린다 (알림톡 4번 · D-050).
	 *
	 * <b>알림이 터져도 마감은 되돌리지 않는다.</b> 예외가 올라가면 이 트랜잭션이 롤백되어
	 * 폼 마감 자체가 없던 일이 되고, 다음 주기에 같은 폼을 또 집는다 — 마감 시각이
	 * 지난 채로 구매 버튼이 계속 켜져 있는 쪽이 알림 한 건보다 나쁘다.
	 */
	private void notifyClosed(Long saleFormId) {
		try {
			for (Order order : orderRepository.findNotifiableBySaleForm(saleFormId)) {
				OrderGroup group = order.getOrderGroup();
				outboxRecorder.record(OutboxAggregate.ORDER_GROUP, group.getId(),
						OutboxEventType.PROGRESS_CHANGED,
						Map.of(
								"orderToken", group.getOrderToken(),
								"buyerId", group.getBuyer().getId(),
								"saleFormId", saleFormId,
								"stage", "CLOSED"));
			}
		} catch (RuntimeException e) {
			log.error("마감 알림 적재 실패(마감은 유지한다): saleFormId={}", saleFormId, e);
		}
	}
}
