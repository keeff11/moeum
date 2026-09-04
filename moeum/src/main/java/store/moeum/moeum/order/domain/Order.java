package store.moeum.moeum.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.DynamicUpdate;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.saleform.domain.SaleForm;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 판매 폼별 주문. 상태 머신이 여기서 돈다 —
 * 폼마다 입고 시점이 다르기 때문에 묶음 단위로는 상태를 표현할 수 없다.
 */
@Entity
@Table(name = "orders")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_group_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_orders_group"))
	private OrderGroup orderGroup;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_form_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_orders_form"))
	private SaleForm saleForm;

	/** 이 폼에서 확보한 총 수량. 홀드 수량과 같아야 한다 */
	@Column(name = "qty", nullable = false)
	private int qty;

	@Column(name = "deposit1_sum", nullable = false)
	private int deposit1Sum;

	@Column(name = "deposit2_sum", nullable = false)
	private int deposit2Sum;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OrderStatus status;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@OneToMany(mappedBy = "order", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<OrderItem> items = new ArrayList<>();

	private Order(SaleForm saleForm) {
		this.saleForm = saleForm;
		this.status = OrderStatus.CREATED;
		this.qty = 0;
		this.deposit1Sum = 0;
		this.deposit2Sum = 0;
	}

	public static Order create(SaleForm saleForm) {
		return new Order(saleForm);
	}

	public List<OrderItem> getItems() {
		return Collections.unmodifiableList(items);
	}

	public void addItem(OrderItem item) {
		items.add(item);
		item.assignTo(this);
		this.qty += item.getQty();
		this.deposit1Sum += item.getDeposit1Amount() * item.getQty();
		this.deposit2Sum += item.getDeposit2Amount() * item.getQty();
	}

	void expire() {
		this.status = OrderStatus.EXPIRED;
	}

	void assignTo(OrderGroup orderGroup) {
		this.orderGroup = orderGroup;
	}
}
