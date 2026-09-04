package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.BuyerService;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.HoldStatus;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.saleform.domain.SaleFormRepository;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderCreator orderCreator;
	private final OrderGroupRepository orderGroupRepository;
	private final StockHoldRepository stockHoldRepository;
	private final SaleFormRepository saleFormRepository;
	private final BuyerService buyerService;

	/**
	 * 주문 생성 + 재고 홀드.
	 *
	 * <b>재시도는 트랜잭션 바깥에 있다.</b> {@link OrderCreator#create} 가 REQUIRES_NEW 로 자기 트랜잭션을
	 * 열고 닫으므로, 실패해서 롤백된 뒤 새 트랜잭션으로 다시 시도한다.
	 * 한 메서드에 @Transactional 과 @Retryable 을 같이 걸면 이미 롤백 표시가 된 트랜잭션 안에서
	 * 재시도하게 되어 두 번째 시도가 반드시 실패한다.
	 *
	 * 재시도 대상은 데드락 · 락 타임아웃뿐이다.
	 * OutOfStockException 은 {@link BusinessException} 이라 아래 retryFor 에 걸리지 않는다 —
	 * 다시 해도 품절이고, 200명이 몰린 상황에서 3회씩 재시도하면 DB 를 600번 두들긴다.
	 */
	@Retryable(
			retryFor = {
					DeadlockLoserDataAccessException.class,
					CannotAcquireLockException.class,
					PessimisticLockingFailureException.class
			},
			maxAttempts = 3,
			backoff = @Backoff(delay = 50, multiplier = 2, random = true))
	public OrderGroupResponse place(SessionUser user, OrderCreateRequest request) {
		Buyer buyer = buyerService.findOrCreate(user);
		return orderCreator.create(buyer, request);
	}

	/**
	 * 재시도가 끝났을 때 불린다.
	 *
	 * ⚠️ 재시도 대상이 아닌 예외(품절 등)에도 불린다 — Spring Retry 는 "더 이상 재시도하지 않음" 시점에
	 * 항상 복구 메서드를 찾는다. 그래서 파라미터를 Exception 으로 넓히고,
	 * 비즈니스 예외는 손대지 않고 그대로 흘려보낸다. 좁게 잡으면 품절이 500 으로 바뀐다.
	 */
	@Recover
	public OrderGroupResponse recover(Exception e, SessionUser user, OrderCreateRequest request) {
		if (e instanceof BusinessException businessException) {
			throw businessException;
		}
		// 3회를 다 쓰고도 락을 못 잡았다. 재시도하면 될 수 있는 오류로 알린다
		log.error("재고 확보 실패(재시도 소진): kakaoId={}", user.kakaoId(), e);
		throw new BusinessException(ErrorCode.TEMPORARY_ERROR);
	}

	@Transactional(readOnly = true)
	public OrderGroupResponse findBySessionToken(SessionUser user, String sessionToken) {
		Buyer buyer = buyerService.getByKakaoId(user.kakaoId());

		OrderGroup group = orderGroupRepository.findBySessionToken(sessionToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (!group.getBuyer().getId().equals(buyer.getId())) {
			throw new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND);
		}
		return toResponse(group);
	}

	/**
	 * 이탈 시 홀드 해제. <b>멱등하다</b> — 두 번 불러도 재고가 두 번 돌아가지 않는다.
	 *
	 * 이 경로는 신뢰할 수 없다. 브라우저 강제 종료·앱 전환이면 호출 자체가 오지 않는다.
	 * 최종 안전망은 만료 배치이고, 여기는 재고 회전을 앞당기는 최적화일 뿐이다.
	 */
	@Transactional
	public void release(SessionUser user, String sessionToken) {
		Buyer buyer = buyerService.getByKakaoId(user.kakaoId());

		OrderGroup group = orderGroupRepository.findBySessionToken(sessionToken)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (!group.getBuyer().getId().equals(buyer.getId())) {
			throw new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND);
		}
		releaseHolds(group);
	}

	/** 만료 배치와 공유한다. 상태 전이가 성공한 홀드만 재고를 되돌린다 */
	@Transactional
	public int releaseHolds(OrderGroup group) {
		List<Long> orderIds = group.getOrders().stream().map(Order::getId).toList();
		if (orderIds.isEmpty()) {
			return 0;
		}

		int released = 0;
		for (StockHold hold : stockHoldRepository.findByOrderIdIn(orderIds)) {
			// ★ 멱등 가드. 이미 RELEASED/COMMITTED 면 재고를 건드리지 않는다
			if (!hold.release()) {
				continue;
			}
			saleFormRepository.releaseHold(hold.getSaleForm().getId(), hold.getQty());
			released++;
		}
		if (released > 0) {
			group.expire();
		}
		return released;
	}

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
