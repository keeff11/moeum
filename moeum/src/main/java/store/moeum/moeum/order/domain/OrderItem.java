package store.moeum.moeum.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;

/**
 * 주문 항목. <b>주문 시점 값을 전부 스냅샷으로 복사한다</b> (D-010).
 *
 * 셀러가 나중에 가격이나 옵션명을 바꿔도 이미 나간 주문의 청구액은 움직이면 안 된다.
 * 금액은 결제 · 환불 · 정산의 근거라 참조로 두면 과거를 재현할 수 없다.
 */
@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_item_order"))
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_item_product"))
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "option_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_item_option"))
	private ProductOption option;

	@Column(name = "qty", nullable = false)
	private int qty;

	@Column(name = "deposit1_amount", nullable = false, updatable = false)
	private int deposit1Amount;

	@Column(name = "deposit2_amount", nullable = false, updatable = false)
	private int deposit2Amount;

	@Column(name = "product_name", nullable = false, length = 200, updatable = false)
	private String productName;

	@Column(name = "option_name", nullable = false, length = 100, updatable = false)
	private String optionName;

	private OrderItem(Product product, ProductOption option, int qty) {
		this.product = product;
		this.option = option;
		this.qty = qty;
		// 스냅샷 — 이 시점 값을 굳힌다
		this.deposit1Amount = option.getDeposit1Amount();
		this.deposit2Amount = option.getDeposit2Amount();
		this.productName = product.getName();
		this.optionName = option.getName();
	}

	public static OrderItem snapshotOf(Product product, ProductOption option, int qty) {
		return new OrderItem(product, option, qty);
	}

	void assignTo(Order order) {
		this.order = order;
	}
}
