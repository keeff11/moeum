package store.moeum.moeum.cart;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.BuyerService;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.cart.domain.Cart;
import store.moeum.moeum.cart.domain.CartItem;
import store.moeum.moeum.cart.domain.CartRepository;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.ProductOptionRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

@Service
@RequiredArgsConstructor
public class CartService {

	private final CartRepository cartRepository;
	private final ProductOptionRepository optionRepository;
	private final BuyerService buyerService;

	/**
	 * 담기. 셀러가 다르면 그 셀러의 장바구니가 따로 생긴다 (교체 아님).
	 * 재고는 잡지 않는다 — 장바구니는 위시리스트에 가깝고, 재고 판정은 주문 생성 때 한 번만 한다.
	 */
	@Transactional
	public Long add(SessionUser user, CartAddRequest request) {
		Buyer buyer = buyerService.findOrCreate(user);

		ProductOption option = optionRepository.findWithFormById(request.optionId())
				.orElseThrow(() -> new BusinessException(ErrorCode.OPTION_NOT_FOUND));

		Product product = option.getProduct();
		SaleForm saleForm = product.getSaleForm();
		Seller seller = saleForm.getSeller();

		if (saleForm.getStatus() == SaleFormStatus.DRAFT) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}

		Cart cart = cartRepository.findByBuyerIdAndSellerId(buyer.getId(), seller.getId())
				.orElseGet(() -> cartRepository.save(Cart.of(buyer, seller)));

		CartItem item = CartItem.builder()
				.saleForm(saleForm)
				.product(product)
				.option(option)
				.qty(request.qty())
				.build();

		CartItem saved = cart.addOrIncrease(item, request.qty());
		cartRepository.flush();
		return saved.getId();
	}

	/** 셀러별로 나뉜 장바구니 전부. 마감 · 품절 상태를 함께 표시한다 */
	@Transactional(readOnly = true)
	public List<CartResponse> findMine(SessionUser user) {
		return buyerService.findByKakaoId(user.kakaoId())
				.map(buyer -> cartRepository.findAllByBuyerId(buyer.getId()).stream()
						.map(this::toResponse)
						.toList())
				.orElseGet(List::of);
	}

	@Transactional
	public void changeQty(SessionUser user, Long cartItemId, int qty) {
		CartItem item = findOwnedItem(user, cartItemId);
		item.changeQty(qty);
	}

	@Transactional
	public void remove(SessionUser user, Long cartItemId) {
		CartItem item = findOwnedItem(user, cartItemId);
		item.getCart().remove(item);
	}

	private CartItem findOwnedItem(SessionUser user, Long cartItemId) {
		Buyer buyer = buyerService.getByKakaoId(user.kakaoId());

		return cartRepository.findAllByBuyerId(buyer.getId()).stream()
				.flatMap(cart -> cart.getItems().stream())
				.filter(item -> item.getId().equals(cartItemId))
				.findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND));
	}

	private CartResponse toResponse(Cart cart) {
		Seller seller = cart.getSeller();
		LocalDateTime now = LocalDateTime.now(KST);

		List<CartResponse.CartItemResponse> items = cart.getItems().stream()
				.map(item -> toItemResponse(item, now))
				.toList();

		int deposit1Total = items.stream()
				.mapToInt(item -> item.deposit1Amount() * item.qty()).sum();
		int deposit2Total = items.stream()
				.mapToInt(item -> item.deposit2Amount() * item.qty()).sum();

		boolean orderable = !items.isEmpty() && items.stream()
				.allMatch(item -> item.status() == CartResponse.ItemStatus.AVAILABLE);

		return new CartResponse(
				cart.getId(), seller.getId(), seller.getStoreSlug(),
				seller.getShippingFee(), seller.getFreeShippingOver(),
				deposit1Total, deposit2Total, orderable, items);
	}

	private CartResponse.CartItemResponse toItemResponse(CartItem item, LocalDateTime now) {
		SaleForm form = item.getSaleForm();
		ProductOption option = item.getOption();

		return new CartResponse.CartItemResponse(
				item.getId(),
				form.getId(),
				form.getTitle(),
				item.getProduct().getId(),
				item.getProduct().getName(),
				option.getId(),
				option.getName(),
				item.getQty(),
				option.getDeposit1Amount(),
				option.getDeposit2Amount(),
				Math.max(form.remainingStock(), 0),
				statusOf(item, form, now));
	}

	/**
	 * 조회 시점의 참고 상태다. 여기서 AVAILABLE 이라도 주문이 성공한다는 보장은 없다 —
	 * 판정은 주문 생성의 조건부 UPDATE 한 곳에서만 한다.
	 */
	private CartResponse.ItemStatus statusOf(CartItem item, SaleForm form, LocalDateTime now) {
		boolean closed = form.getStatus() != SaleFormStatus.SELLING
				|| (form.getClosesAt() != null && !form.getClosesAt().isAfter(now));
		if (closed) {
			return CartResponse.ItemStatus.CLOSED;
		}
		if (form.remainingStock() <= 0) {
			return CartResponse.ItemStatus.SOLD_OUT;
		}
		if (form.getMaxPerUser() != null && item.getQty() > form.getMaxPerUser()) {
			return CartResponse.ItemStatus.MAX_PER_USER_EXCEEDED;
		}
		if (item.getQty() > form.remainingStock()) {
			return CartResponse.ItemStatus.NOT_ENOUGH_STOCK;
		}
		return CartResponse.ItemStatus.AVAILABLE;
	}
}
