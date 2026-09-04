package store.moeum.moeum.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;

import java.time.LocalDateTime;

@Entity
@Table(name = "cart_item")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cart_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cart_item_cart"))
	private Cart cart;

	/** 담을 때 cart.seller_id 와 일치를 검증한다 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_form_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_cart_item_form"))
	private SaleForm saleForm;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_cart_item_product"))
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "option_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_cart_item_option"))
	private ProductOption option;

	@Column(name = "qty", nullable = false)
	private int qty;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	private CartItem(SaleForm saleForm, Product product, ProductOption option, int qty) {
		this.saleForm = saleForm;
		this.product = product;
		this.option = option;
		this.qty = qty;
	}

	boolean hasSameOption(CartItem other) {
		return option.getId().equals(other.getOption().getId());
	}

	void increase(int amount) {
		this.qty += amount;
	}

	public void changeQty(int qty) {
		this.qty = qty;
	}

	void assignTo(Cart cart) {
		this.cart = cart;
	}
}
