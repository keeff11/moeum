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
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;

/**
 * 2차금 청구 이력 (와이어프레임 S10).
 *
 * <b>청구는 알림을 다시 내보내는 것이고 주문 상태를 바꾸지 않는다</b> (D-035).
 * 그래서 "청구했다" 는 사실이 order_group 어디에도 남지 않는다 — 이 행이 그 기록이다.
 * 쿨다운 판정과 화면의 "마지막 청구일" 이 여기서 나온다.
 *
 * 묶음당 여러 행이 쌓인다. 미납이 이어지면 다시 청구하는 것이 정상이다.
 */
@Entity
@Table(name = "second_charge")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecondCharge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_group_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_charge_group"))
	private OrderGroup orderGroup;

	/** order_group 을 타고 가도 알 수 있지만, 셀러 기준 조회가 이 표의 주 용도다 */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_charge_seller"))
	private Seller seller;

	/**
	 * 청구 시점의 2차금. <b>스냅샷이다</b> — 나중에 폼이 취소되면 청구액이 줄어드는데,
	 * 그때 "얼마로 청구했었나" 를 되짚을 수 있어야 한다.
	 */
	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "charged_at", nullable = false)
	private LocalDateTime chargedAt;

	private SecondCharge(OrderGroup orderGroup, int amount, LocalDateTime chargedAt) {
		this.orderGroup = orderGroup;
		this.seller = orderGroup.getSeller();
		this.amount = amount;
		this.chargedAt = chargedAt;
	}

	public static SecondCharge of(OrderGroup orderGroup, LocalDateTime chargedAt) {
		return new SecondCharge(orderGroup, orderGroup.secondPaymentAmount(), chargedAt);
	}
}
