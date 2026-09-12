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
import store.moeum.moeum.saleform.domain.SaleType;

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

	/** 입고로 넘어올 수 있는 상태. 1차금이 확정된 뒤의 진행 단계 전부다 (D-049) */
	private static final java.util.Set<OrderStatus> ARRIVABLE = java.util.EnumSet.of(
			OrderStatus.PAID, OrderStatus.RECRUITING, OrderStatus.CLOSED, OrderStatus.PRODUCING);

	void expire() {
		this.status = OrderStatus.EXPIRED;
	}

	/**
	 * 1차금 확정. 묶음이 PAID 로 갈 때 주문들도 같이 넘어간다.
	 *
	 * <b>공동구매는 바로 모집 중으로 간다</b> (D-049). domain.md 3절의 흐름이
	 * {@code CREATED → PAID → RECRUITING → ...} 인데, RECRUITING 으로 올리는 코드가
	 * 없어서 구매자 화면이 입고될 때까지 "결제 완료" 로만 보였다.
	 *
	 * 단독은 PAID 에 머문다. 모집이라는 개념이 없어서 흐름이
	 * {@code 결제완료 → 준비중 → 발송} 이다 (domain.md 1절).
	 */
	void markPaid() {
		if (status != OrderStatus.CREATED) {
			return;
		}
		this.status = (saleForm.getSaleType() == SaleType.GROUP)
				? OrderStatus.RECRUITING
				: OrderStatus.PAID;
	}

	/**
	 * 모집 마감. 판매 폼이 마감될 때 그 폼의 주문들이 같이 넘어간다 (D-049).
	 *
	 * <b>모집 중인 것만 넘긴다.</b> 이미 발주(PRODUCING)나 입고로 올라간 주문을
	 * 마감으로 되돌리면 진행이 거꾸로 간다 — 셀러가 수동 마감을 다시 눌러도 안전해야 한다.
	 */
	public boolean markClosed() {
		if (status != OrderStatus.RECRUITING) {
			return false;
		}
		this.status = OrderStatus.CLOSED;
		return true;
	}

	/**
	 * 발주 · 제작 시작 (D-049). 셀러가 누른다.
	 *
	 * 마감된 것만 넘긴다. 모집이 끝나야 몇 개를 만들지가 정해지고, 그 전에 발주하면
	 * 발주서(D-045)의 수량과 어긋난다.
	 */
	public boolean markProducing() {
		if (status == OrderStatus.PRODUCING) {
			return false;
		}
		if (status != OrderStatus.CLOSED) {
			throw new IllegalStateException("발주 처리할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderStatus.PRODUCING;
		return true;
	}

	/**
	 * 입고 완료. 2차금 청구의 시작점이다 (5단계).
	 *
	 * 1차금이 확정된 주문만 넘길 수 있다 — 돈을 안 받은 주문을 입고 처리하면
	 * 2차금 청구 대상에 섞여 잔금만 청구하게 된다.
	 *
	 * <b>중간 단계 어디서든 올 수 있다</b> (D-049). 공동구매는 모집 → 마감 → 발주를
	 * 거치는데, PAID 만 허용하면 그 단계를 밟은 주문이 입고 처리에서 막혀 2차금이
	 * 영영 안 열린다. 셀러가 중간 단계를 건너뛰고 바로 입고를 누르는 것도 막지 않는다 —
	 * 실제로 그렇게 일하는 셀러가 있고, 막아서 얻는 것이 없다.
	 *
	 * @return 이번 호출로 바뀌었으면 true
	 */
	public boolean markArrived() {
		if (status == OrderStatus.ARRIVED) {
			return false;
		}
		if (!ARRIVABLE.contains(status)) {
			throw new IllegalStateException("입고 처리할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderStatus.ARRIVED;
		return true;
	}

	public boolean isArrived() {
		return status == OrderStatus.ARRIVED;
	}

	/**
	 * 발송 완료. 송장이 등록될 때 묶음이 주문들을 같이 넘긴다 (D-047).
	 *
	 * 입고된 주문만 넘어간다 — 아직 물건이 안 들어왔는데 보냈다고 할 수는 없다.
	 * 이미 SHIPPED 면 조용히 넘어간다: 셀러가 송장번호를 고칠 수 있어야 하고,
	 * 그때마다 예외가 나면 수정 자체가 막힌다.
	 */
	boolean markShipped() {
		if (status == OrderStatus.SHIPPED) {
			return false;
		}
		if (status != OrderStatus.ARRIVED) {
			throw new IllegalStateException("발송 처리할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderStatus.SHIPPED;
		return true;
	}

	/**
	 * 취소 확정. <b>멱등하다</b> — 실시간 취소와 대사 배치가 같은 건을 확정할 수 있다.
	 *
	 * 환불이 끝난 뒤에만 부른다. 요청만 받고 미리 바꾸면 취소가 거절됐을 때
	 * 돈은 그대로인데 주문만 사라진다.
	 *
	 * @return 이번 호출로 바뀌었으면 true
	 */
	public boolean cancel(LocalDateTime at) {
		if (status == OrderStatus.CANCELED) {
			return false;
		}
		this.status = OrderStatus.CANCELED;
		this.canceledAt = at;
		return true;
	}

	public boolean isCanceled() {
		return status == OrderStatus.CANCELED;
	}

	void assignTo(OrderGroup orderGroup) {
		this.orderGroup = orderGroup;
	}
}
