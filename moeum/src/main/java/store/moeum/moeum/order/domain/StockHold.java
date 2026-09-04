package store.moeum.moeum.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import store.moeum.moeum.saleform.domain.SaleForm;

import java.time.LocalDateTime;

/**
 * 재고 선점 기록. 주문(=판매 폼) 하나당 하나다 (uk_hold_order).
 *
 * <b>이 행은 sale_form.held 의 근거일 뿐 재고 자체가 아니다.</b>
 * 실제 수량은 sale_form 의 조건부 UPDATE 로만 움직인다. 여기 값을 고쳐도 재고는 변하지 않는다.
 */
@Entity
@Table(name = "stock_hold")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHold {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_hold_order"))
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_form_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_hold_form"))
	private SaleForm saleForm;

	@Column(name = "qty", nullable = false, updatable = false)
	private int qty;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private HoldStatus status;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private StockHold(Order order, SaleForm saleForm, int qty, LocalDateTime expiresAt) {
		this.order = order;
		this.saleForm = saleForm;
		this.qty = qty;
		this.expiresAt = expiresAt;
		this.status = HoldStatus.HELD;
	}

	public static StockHold held(Order order, SaleForm saleForm, int qty, LocalDateTime expiresAt) {
		return new StockHold(order, saleForm, qty, expiresAt);
	}

	public boolean isHeld() {
		return status == HoldStatus.HELD;
	}

	/**
	 * 상태만 바꾼다. sale_form 의 수량 이동은 호출자가 조건부 UPDATE 로 따로 한다.
	 * 이미 HELD 가 아니면 false — 배치가 같은 건을 두 번 처리해도 재고가 두 번 움직이지 않는다.
	 */
	public boolean release() {
		if (!isHeld()) {
			return false;
		}
		this.status = HoldStatus.RELEASED;
		return true;
	}

	public boolean commit() {
		if (!isHeld()) {
			return false;
		}
		this.status = HoldStatus.COMMITTED;
		return true;
	}
}
