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
}
