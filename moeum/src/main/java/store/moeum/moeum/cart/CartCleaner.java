package store.moeum.moeum.cart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.cart.domain.Cart;
import store.moeum.moeum.cart.domain.CartItem;
import store.moeum.moeum.cart.domain.CartRepository;

import java.util.Collection;
import java.util.List;

/**
 * 결제가 끝난 항목을 장바구니에서 뺀다.
 *
 * <b>담아 둔 채로 두면 같은 것을 또 주문하게 된다.</b> 장바구니는 재고를 잡지 않으므로
 * 결제한 뒤에도 그대로 남고, 구매자는 이미 산 것인지 아닌지를 화면만 보고 알 수 없다.
 *
 * <b>홀드 시점이 아니라 결제 확정 시점에 뺀다.</b> 주문 생성(= 재고 홀드)에서 비우면
 * 결제창을 닫거나 승인이 실패한 구매자의 장바구니가 사라진다 — 홀드는 15분 뒤 만료 배치가
 * 걷어 가는데 장바구니는 되돌려 줄 방법이 없다. 홀드가 중복으로 잡히는 문제는
 * 장바구니가 아니라 세션 이어받기가 막는다 (D-037).
 *
 * <b>별도 트랜잭션이다.</b> 여기서 터져도 결제 확정이 같이 롤백되면 안 된다 —
 * 돈은 빠져나갔는데 결제가 CAPTURE_PENDING 으로 남는 쪽이 장바구니가 안 비워지는 것보다
 * 훨씬 나쁘다. 구매자가 다른 탭에서 장바구니를 만지는 중이면 락 대기로 실패할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartCleaner {

	private final CartRepository cartRepository;

	/**
	 * 결제한 옵션과 같은 항목을 지운다. 셀러별로 나뉜 장바구니를 모두 훑는다.
	 *
	 * <b>수량은 보지 않고 줄째로 지운다.</b> 장바구니에 3개를 담고 2개만 결제하는 경로가
	 * 열려 있긴 하지만(B2 에서 수량을 다시 고른다), 남은 1개를 장바구니에 남겨 두면
	 * 구매자는 그것이 사다 만 것인지 새로 담은 것인지 알 수 없다.
	 *
	 * 비게 된 장바구니는 행째로 지운다. 두면 셀러 묶음이 빈 채로 화면에 남는다 —
	 * 다시 담으면 {@link CartService#add} 가 새로 만든다.
	 *
	 * @return 지운 항목 수
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int removeOrdered(Long buyerId, Collection<Long> optionIds) {
		if (optionIds.isEmpty()) {
			return 0;
		}

		int removed = 0;
		for (Cart cart : cartRepository.findAllByBuyerId(buyerId)) {
			// 지우면서 순회하면 컬렉션이 흔들린다. 대상을 먼저 추린다
			List<CartItem> targets = cart.getItems().stream()
					.filter(item -> optionIds.contains(item.getOption().getId()))
					.toList();

			targets.forEach(cart::remove);
			removed += targets.size();

			if (cart.getItems().isEmpty()) {
				cartRepository.delete(cart);
			}
		}
		return removed;
	}
}
