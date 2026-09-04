package store.moeum.moeum.cart.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.seller.domain.Seller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 장바구니. <b>구매자 × 셀러</b> 하나당 하나다 (uk_cart_buyer_seller).
 *
 * 다른 셀러 상품을 담으면 그 셀러의 장바구니가 따로 생긴다. 교체가 아니다.
 * 배송비가 셀러 단위 · 묶음당 1회라, 한 주문에 여러 셀러가 섞이면 배송비를 나눌 수 없다.
 *
 * 장바구니는 재고를 잡지 않는다. 담아둔 사이 마감 · 품절되면 주문 생성 시 걸러진다.
 */
@Entity
@Table(name = "cart")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buyer_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_cart_buyer"))
	private Buyer buyer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_cart_seller"))
	private Seller seller;

	@OneToMany(mappedBy = "cart", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<CartItem> items = new ArrayList<>();

	private Cart(Buyer buyer, Seller seller) {
		this.buyer = buyer;
		this.seller = seller;
	}

	public static Cart of(Buyer buyer, Seller seller) {
		return new Cart(buyer, seller);
	}

	public List<CartItem> getItems() {
		return Collections.unmodifiableList(items);
	}

	/**
	 * 담기. 같은 옵션을 다시 담으면 수량을 더한다 (uk_cart_item 이 cart_id + option_id).
	 * 재고 검사는 여기서 하지 않는다 — 주문 생성 시점의 조건부 UPDATE 가 유일한 판정이다.
	 */
	public CartItem addOrIncrease(CartItem item, int qty) {
		Optional<CartItem> existing = items.stream()
				.filter(each -> each.hasSameOption(item))
				.findFirst();

		if (existing.isPresent()) {
			existing.get().increase(qty);
			return existing.get();
		}
		items.add(item);
		item.assignTo(this);
		return item;
	}

	public void remove(CartItem item) {
		items.remove(item);
		item.assignTo(null);
	}

	public void clear() {
		items.forEach(item -> item.assignTo(null));
		items.clear();
	}
}
