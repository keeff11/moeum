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
import java.time.format.DateTimeFormatter;
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

	private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");

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

	/**
	 * ORD-YYMMDD-{id} — 사람이 읽고 부르는 주문번호. order_token 과 같이 /pay 에서 발급한다.
	 *
	 * 토큰과 나누는 이유는 쓰임이 반대라서다. order_token 은 주소창에 실려 나가므로
	 * 추측할 수 없어야 하고, 이 값은 셀러와 구매자가 전화로 주고받아야 하므로 읽을 수 있어야 한다.
	 */
	@Column(name = "order_no", length = 20)
	private String orderNo;

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

	/**
	 * 상품 총액 = 1차금 + 2차금. <b>배송비는 빼고 센다.</b>
	 *
	 * 무료배송 기준이 보는 값이다 — 배송비를 포함하면 배송비 덕분에 배송비가 면제되는
	 * 순환이 된다. api-spec 이 옵션가를 deposit1 + deposit2 로 정의하고 배송비를
	 * 거기서 빼는 것과 같은 기준이다.
	 */
	public int productTotal() {
		return deposit1Total + deposit2Total;
	}

	/**
	 * 배송비를 확정한다. <b>주문 항목을 다 담은 뒤에 부른다.</b>
	 *
	 * 생성자에서 정할 수 없다 — 무료배송 기준은 상품 총액을 보는데,
	 * 그 합계는 addOrder 가 끝나야 나온다.
	 *
	 * 한 번 정해지면 그대로 굳는다. 나중에 폼 하나를 취소해서 총액이 기준 아래로
	 * 떨어져도 소급해서 배송비를 물리지 않는다 — 취소했더니 없던 배송비가 생기는 것은
	 * 구매자가 납득할 수 없다.
	 */
	public void applyShippingFee(int fee) {
		this.shippingFee = fee;
	}

	/**
	 * 2차금 청구액. 배송비는 묶음당 1회라 여기서 한 번만 더한다.
	 *
	 * <b>취소된 폼의 잔금은 빼고 센다</b> (D-035). {@code deposit2Total} 은 주문을 담을 때
	 * 누적된 스냅샷이라 폼 하나를 취소해도 줄지 않는다 — 그대로 쓰면 이미 환불한 상품의
	 * 잔금까지 청구하게 된다.
	 *
	 * <b>배송비는 굳힌 값을 그대로 쓴다.</b> 취소로 총액이 줄었다고 무료배송이 풀려
	 * 배송비가 되살아나면 안 된다 ({@link #applyShippingFee} 와 D-032 가 같은 이유다).
	 */
	public int secondPaymentAmount() {
		if (!hasSecondPayment()) {
			// 배송비까지 1차금에서 받았다. 여기서 또 더하면 이중 청구다
			return 0;
		}

		int deposit2 = activeOrders().stream()
				.mapToInt(Order::getDeposit2Sum)
				.sum();

		return deposit2 + shippingFee;
	}

	/**
	 * 이 묶음에 2차금이 있는가. <b>배송비가 어디로 갈지를 정하는 값이다.</b>
	 *
	 * 단독 판매(SOLO)는 2차금이 없다 (domain.md 1절 — {@code second_type = NONE}).
	 * 그래서 폼을 만들 때 deposit2 를 0 으로 강제한다. 공동구매라도 전액 선결제면
	 * 마찬가지로 0 이다. <b>유형이 아니라 실제 금액을 보는 이유가 이것이다</b> —
	 * "SOLO 인가" 로 물으면 전액 선결제 공구가 빠진다.
	 *
	 * <b>누적 스냅샷인 {@code deposit2Total} 을 본다.</b> 살아 있는 주문으로 세면
	 * 폼 하나가 취소될 때 배송비의 자리가 1차금과 2차금 사이에서 움직인다 —
	 * 이미 받은 1차금은 소급해서 못 바꾸므로 판정은 주문 생성 시점에 굳어야 한다.
	 */
	public boolean hasSecondPayment() {
		return deposit2Total > 0;
	}

	public void expire() {
		this.status = OrderGroupStatus.EXPIRED;
		this.orders.forEach(Order::expire);
	}

	/**
	 * 1차금 청구액.
	 *
	 * <b>배송비는 2차금이 있을 때만 뒤로 미룬다.</b> 배송비는 묶음당 1회이고 원래
	 * 2차금으로 이연된다 (api-spec 6절 "B5 청구액은 옵션가뿐"). 1차금에서도 더하면
	 * 같은 배송비를 두 번 청구하게 된다.
	 *
	 * <b>그런데 2차금이 없는 묶음은 여기서 안 받으면 영영 못 받는다</b> (D-046).
	 * 단독 판매는 2차금 단계 자체를 건너뛰므로(domain.md 1절), 이연하면 배송비를
	 * 청구할 자리가 사라져 셀러가 배송비를 떠안는다. 그래서 2차금이 없으면 1차금에 싣는다.
	 */
	public int firstPaymentAmount() {
		return deposit1Total + (hasSecondPayment() ? 0 : shippingFee);
	}

	/**
	 * 결제 세션을 만들어 결제창으로 보낼 준비가 된 상태.
	 *
	 * 재결제로 다시 들어올 수 있어 PAY_PENDING 에서 또 불려도 그대로 둔다 (D-023).
	 * 복귀 페이지 주소로 쓸 orderToken 과 셀러·구매자가 부를 orderNo 를 이때 발급한다.
	 *
	 * <b>재결제에서 번호가 바뀌지 않는다.</b> 실패해서 다시 결제한 것은 같은 주문이라,
	 * 번호가 갈리면 셀러와 구매자가 서로 다른 번호를 들고 이야기하게 된다.
	 */
	public void markPayPending(String orderToken) {
		if (status != OrderGroupStatus.CREATED && status != OrderGroupStatus.PAY_PENDING) {
			throw new IllegalStateException("결제를 시작할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderGroupStatus.PAY_PENDING;
		if (this.orderToken == null) {
			this.orderToken = orderToken;
		}
		if (this.orderNo == null) {
			this.orderNo = issueOrderNo();
		}
	}

	/**
	 * ORD-{yyMMdd}-{id}.
	 *
	 * 그날의 순번을 쓰지 않는다 — 순번을 매기려면 카운터를 잠가야 하고, 결제 시작 경로에
	 * 락을 하나 더 놓을 값이 아니다. id 가 이미 유일하므로 날짜와 붙이면 그대로 유일하다.
	 */
	private String issueOrderNo() {
		return "ORD-" + getCreatedAt().format(ORDER_NO_DATE) + "-" + id;
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
	 *
	 * <b>2차금이 없는 묶음은 애초에 대상이 아니다</b> (D-046) — 단독 판매가 그렇다.
	 * 빼지 않으면 배송비를 1차금에서 이미 받은 주문이 "2차금 미납" 탭에 쌓인다.
	 *
	 * <b>기준은 살아 있는 주문이다</b> (D-035). 취소된 폼까지 입고를 요구하면 그 폼은
	 * 영원히 CANCELED 라 조건이 영영 참이 되지 않는다 — 부분 취소된 묶음의 잔금을
	 * 영영 못 받는다. 셀러 목록의 '2차금 미납' 판정도 같은 기준을 쓴다.
	 */
	public boolean isSecondPaymentDue() {
		// 2차금이 없는 묶음은 배송비까지 1차금에서 받았다. 청구할 것이 남지 않았다
		return hasSecondPayment()
				&& status == OrderGroupStatus.PAID
				&& isAllAliveArrived();
	}

	/**
	 * 잔금 없이 발송만 남았는가 (D-046).
	 *
	 * <b>단독 판매가 여기로 온다.</b> 2차금이 있는 묶음은 입고돼도 잔금을 받아야 발송
	 * 단계가 되지만(그때 {@code SECOND_PAID} 로 넘어간다), 2차금이 없으면 입고가 곧
	 * 발송 준비 완료다. 받을 돈이 더 없다.
	 *
	 * <b>이게 없으면 단독 판매 주문이 어느 화면에서도 발송 대기로 보이지 않는다.</b>
	 * 묶음 상태는 {@code PAID} 에 머무는데(SOLO 는 SECOND_PENDING · SECOND_PAID 를
	 * 건너뛴다 — domain.md 3절), 발송 준비 중 판정이 {@code SECOND_PAID} 만 보고 있었다.
	 * 셀러는 보낼 주문을 못 찾고, 구매자는 이미 도착한 물건을 "제작 중" 으로 본다.
	 *
	 * 묶음 상태를 억지로 {@code SECOND_PAID} 로 올리지 않는 이유가 그 문서다 —
	 * 단독 판매는 그 상태를 지나지 않기로 한 것이고, 여기서 뒤집으면 상태 이름이 거짓이 된다.
	 */
	public boolean isReadyToShipWithoutSecond() {
		return !hasSecondPayment()
				&& status == OrderGroupStatus.PAID
				&& isAllAliveArrived();
	}

	/**
	 * 송장을 등록할 수 있는 단계인가 (D-047).
	 *
	 * <b>받을 돈이 남아 있으면 아직 아니다.</b> 2차금이 있는 묶음은 잔금까지 받아야
	 * ({@code SECOND_PAID}) 하고, 없는 묶음은 입고가 곧 발송 준비 완료다 (D-046).
	 * 셀러 화면의 "배송 준비 중" 과 같은 조건이라 그 탭에 뜬 주문이 곧 등록 대상이다.
	 */
	public boolean isReadyToShip() {
		return status == OrderGroupStatus.SECOND_PAID || isReadyToShipWithoutSecond();
	}

	/**
	 * 송장을 적을 수 있는 상태인가 (D-047).
	 *
	 * <b>이미 발송된 묶음도 참이다.</b> 셀러가 송장번호를 잘못 적는 일이 실제로 있고,
	 * {@link #isReadyToShip()} 만 보면 발송 처리된 순간부터 수정이 막힌다 —
	 * 그러면 구매자가 엉뚱한 배송을 조회하게 된다.
	 */
	public boolean canRegisterShipment() {
		return status == OrderGroupStatus.SHIPPED || isReadyToShip();
	}

	/**
	 * 발송 완료로 넘긴다. 송장이 등록될 때 부른다 (D-047).
	 *
	 * <b>멱등하다.</b> 이미 SHIPPED 면 false 를 주고 아무것도 하지 않는다 — 셀러가
	 * 송장번호를 잘못 적어 고치는 경우가 있고, 그때 상태를 다시 넘기거나 발송 알림을
	 * 또 보내면 안 된다. 알림 적재를 이 반환값 안쪽에 두는 이유다.
	 *
	 * 취소된 주문은 넘기지 않는다 — 보내지 않은 물건이다.
	 */
	public boolean markShipped() {
		if (status == OrderGroupStatus.SHIPPED) {
			return false;
		}
		if (!isReadyToShip()) {
			throw new IllegalStateException("발송 처리할 수 없는 주문 상태다: " + status + " (id=" + id + ")");
		}
		this.status = OrderGroupStatus.SHIPPED;
		activeOrders().forEach(Order::markShipped);
		return true;
	}

	/**
	 * 살아 있는 주문이 전부 입고됐는가.
	 *
	 * 하나도 없으면 거짓이다 — 전부 취소된 묶음은 "입고 안 된 것이 없다" 가 참이 되어
	 * 그냥 두면 청구 · 발송 대상에 섞인다 (D-035).
	 */
	private boolean isAllAliveArrived() {
		List<Order> alive = activeOrders();

		return !alive.isEmpty() && alive.stream().allMatch(Order::isArrived);
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
	/**
	 * 이 묶음을 한 줄로 부르는 이름 — "아크릴 스탠드 외 1건".
	 *
	 * 셀러 주문 카드(G6)와 알림톡 본문이 같은 문구를 쓴다. 두 군데서 따로 만들면
	 * 구매자가 받은 알림과 셀러가 보는 목록의 제목이 어긋난다.
	 *
	 * <b>취소된 폼은 세지 않는다.</b> 남은 주문의 수가 지금 유효한 건수다.
	 */
	public String representativeTitle() {
		List<Order> alive = activeOrders();

		if (alive.isEmpty()) {
			// 전부 취소된 묶음. 그래도 무엇이었는지는 보여야 한다
			return orders.isEmpty() ? "" : orders.get(0).getSaleForm().getTitle();
		}
		String head = alive.get(0).getSaleForm().getTitle();
		return alive.size() == 1 ? head : head + " 외 " + (alive.size() - 1) + "건";
	}

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
