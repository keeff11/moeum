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
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 결제 1회 · 배송지 1개 · 배송비 1회의 단위. 한 묶음은 한 셀러로 제한된다.
 *
 * checkout_session 이 곧 이 엔티티다 (CREATED 상태). 별도 테이블이 아니다.
 * 홀드가 잡히는 순간 만들어지고, 결제하기 시점에 order_token 을 받는다.
 */
@Entity
@Table(name = "order_group")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderGroup extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	/** cs_xxx — 옵션·수량 확정 시 발급 */
	@Column(name = "session_token", nullable = false, length = 40, updatable = false)
	private String sessionToken;

	/** ord_xxx — /pay 시점에 발급 */
	@Column(name = "order_token", length = 40)
	private String orderToken;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buyer_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_group_buyer"))
	private Buyer buyer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_group_seller"))
	private Seller seller;

	/** 1차금 합계 — B5 청구액 */
	@Column(name = "deposit1_total", nullable = false)
	private int deposit1Total;

	/** 2차금 상품 잔금 합계 */
	@Column(name = "deposit2_total", nullable = false)
	private int deposit2Total;

	/** 셀러 배송비 1회분 스냅샷. 주문 시점 값을 굳힌다 */
	@Column(name = "shipping_fee", nullable = false)
	private int shippingFee;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OrderGroupStatus status;

	@Column(name = "fail_reason", length = 100)
	private String failReason;

	@Column(name = "canceled_at")
	private LocalDateTime canceledAt;

	@OneToMany(mappedBy = "orderGroup", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	private final List<Order> orders = new ArrayList<>();

	private OrderGroup(String sessionToken, Buyer buyer, Seller seller, int shippingFee) {
		this.sessionToken = sessionToken;
		this.buyer = buyer;
		this.seller = seller;
		this.shippingFee = shippingFee;
		this.status = OrderGroupStatus.CREATED;
		this.deposit1Total = 0;
		this.deposit2Total = 0;
	}

	public static OrderGroup create(String sessionToken, Buyer buyer, Seller seller, int shippingFee) {
		return new OrderGroup(sessionToken, buyer, seller, shippingFee);
	}

	public List<Order> getOrders() {
		return Collections.unmodifiableList(orders);
	}

	public void addOrder(Order order) {
		orders.add(order);
		order.assignTo(this);
		this.deposit1Total += order.getDeposit1Sum();
		this.deposit2Total += order.getDeposit2Sum();
	}

	/** 2차금 청구액. 배송비는 묶음당 1회라 여기서 한 번만 더한다 */
	public int secondPaymentAmount() {
		return deposit2Total + shippingFee;
	}

	public void expire() {
		this.status = OrderGroupStatus.EXPIRED;
		this.orders.forEach(Order::expire);
	}

	/**
	 * 1차금 청구액 — <b>배송비는 넣지 않는다</b>.
	 *
	 * 배송비는 묶음당 1회이고 2차금으로 이연된다 (api-spec 6절 "B5 청구액은 옵션가뿐").
	 * 여기서도 더하면 같은 배송비를 1차금·2차금 두 번 청구하게 된다.
	 */
	public int firstPaymentAmount() {
		return deposit1Total;
	}

	/**
	 * 결제 세션을 만들어 결제창으로 보낼 준비가 된 상태.
	 *
	 * 재결제로 다시 들어올 수 있어 PAY_PENDING 에서 또 불려도 그대로 둔다 (D-023).
	 * 복귀 페이지 주소로 쓸 orderToken 을 이때 발급한다 — 세션 토큰과 다른 값이다.
	 */
	public void markPayPending(String orderToken) {
		if (status != OrderGroupStatus.CREATED && status != OrderGroupStatus.PAY_PENDING) {
			throw new IllegalStateException("결제를 시작할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderGroupStatus.PAY_PENDING;
		if (this.orderToken == null) {
			this.orderToken = orderToken;
		}
	}

	/**
	 * 1차금 확정. <b>멱등하다</b> — 실시간 처리와 대사 배치가 같은 건을 확정할 수 있다.
	 *
	 * @return 이번 호출로 바뀌었으면 true. false 면 이미 확정돼 재고를 또 차감하면 안 된다
	 */
	public boolean markPaid() {
		if (status == OrderGroupStatus.PAID) {
			return false;
		}
		this.status = OrderGroupStatus.PAID;
		this.failReason = null;
		markOrdersPaid();
		return true;
	}

	/**
	 * 결제 실패. 홀드 해제는 호출자가 따로 한다 — 여기서 재고를 건드리지 않는다.
	 * 이미 확정된 묶음은 실패로 내리지 않는다.
	 */
	public boolean markPaymentFailed(String reason) {
		if (status == OrderGroupStatus.PAID || status == OrderGroupStatus.FAILED) {
			return false;
		}
		this.status = OrderGroupStatus.FAILED;
		this.failReason = (reason == null || reason.length() <= 100)
				? reason : reason.substring(0, 100);
		return true;
	}

	public boolean isPaid() {
		return status == OrderGroupStatus.PAID;
	}

	/**
	 * 2차금 청구 대상인가 — <b>묶음의 모든 주문이 입고돼야 한다</b> (payment-flow 2절).
	 *
	 * 배송비가 묶음당 1회라 일부만 입고됐다고 청구하면 배송비를 나눌 방법이 없다.
	 * 한 폼이라도 늦어지면 그 묶음 전체가 기다린다.
	 */
	public boolean isSecondPaymentDue() {
		return status == OrderGroupStatus.PAID
				&& !orders.isEmpty()
				&& orders.stream().allMatch(Order::isArrived);
	}

	/** 2차금 결제 세션을 만들 준비가 된 상태 */
	public void markSecondPending() {
		if (status != OrderGroupStatus.PAID && status != OrderGroupStatus.SECOND_PENDING) {
			throw new IllegalStateException("2차금을 시작할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderGroupStatus.SECOND_PENDING;
	}

	/**
	 * 2차금 확정. <b>멱등하다.</b>
	 *
	 * 1차금과 달리 홀드 확정이 없다 — 재고는 1차금에서 이미 확정됐다.
	 */
	public boolean markSecondPaid() {
		if (status == OrderGroupStatus.SECOND_PAID) {
			return false;
		}
		this.status = OrderGroupStatus.SECOND_PAID;
		this.failReason = null;
		return true;
	}

	public boolean isSecondPaid() {
		return status == OrderGroupStatus.SECOND_PAID;
	}

	/**
	 * 묶음의 주문이 <b>전부</b> 취소됐으면 묶음도 취소로 내린다.
	 *
	 * 폼 하나만 취소된 묶음은 그대로 둔다 — 남은 폼은 계속 배송돼야 하고,
	 * 묶음을 CANCELED 로 내리면 2차금 청구 대상에서도 빠진다.
	 *
	 * @return 이번 호출로 바뀌었으면 true
	 */
	public boolean cancelIfAllOrdersCanceled(LocalDateTime at) {
		if (status == OrderGroupStatus.CANCELED || orders.isEmpty()) {
			return false;
		}
		if (!orders.stream().allMatch(Order::isCanceled)) {
			return false;
		}
		this.status = OrderGroupStatus.CANCELED;
		this.canceledAt = at;
		return true;
	}

	/** 아직 살아 있는 주문. 취소 대상과 배송비 판정의 기준이다 */
	public List<Order> activeOrders() {
		return orders.stream()
				.filter(o -> !o.isCanceled() && o.getStatus() != OrderStatus.EXPIRED)
				.toList();
	}

	/** 1차금 확정 시 주문들도 같이 PAID 로 넘긴다 */
	public void markOrdersPaid() {
		orders.forEach(Order::markPaid);
	}
}
