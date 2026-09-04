package store.moeum.moeum.saleform.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 옵션. 금액을 절대값으로 갖는다 — v3 에서 base_price + extra_price 방식을 폐기했다.
 * product_option 테이블에는 시각 컬럼이 없다.
 */
@Entity
@Table(name = "product_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_option_product"))
	private Product product;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** 1차금 절대값 — 주문 시 결제한다. 상품가에 더하는 값이 아니다 */
	@Column(name = "deposit1_amount", nullable = false)
	private int deposit1Amount;

	/** 2차금 상품 잔금. 1차금이 전액이면 0 */
	@Column(name = "deposit2_amount", nullable = false)
	private int deposit2Amount;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Builder
	private ProductOption(String name, int deposit1Amount, int deposit2Amount, int sortOrder) {
		this.name = name;
		this.deposit1Amount = deposit1Amount;
		this.deposit2Amount = deposit2Amount;
		this.sortOrder = sortOrder;
	}

	/** 옵션 총액 = 1차금 + 2차금 잔금. 배송비는 셀러 단위라 여기 포함하지 않는다 */
	public int totalAmount() {
		return deposit1Amount + deposit2Amount;
	}

	/** 연관관계 주인 쪽 설정. {@link Product#addOption} 을 통해서만 부른다 */
	void assignTo(Product product) {
		this.product = product;
	}
}
