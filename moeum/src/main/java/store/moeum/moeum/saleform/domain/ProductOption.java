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
import org.hibernate.annotations.DynamicUpdate;

/**
 * 상품 옵션. 금액을 절대값으로 갖는다 — v3 에서 base_price + extra_price 방식을 폐기했다.
 * product_option 테이블에는 시각 컬럼이 없다.
 *
 * <b>옵션 재고 (D-054).</b> {@code stockMax} 가 null 이면 이 옵션은 상한이 없고 폼 재고만 따른다.
 * 값이 있으면 폼 재고와 옵션 재고를 둘 다 통과해야 살 수 있다.
 * held / sold 는 {@link SaleForm} 과 같은 이유로 이 클래스에서 바꾸지 않는다 —
 * 조건부 UPDATE 전용이고 읽기만 한다.
 */
@Entity
@Table(name = "product_option")
@DynamicUpdate
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

	/** 옵션 재고 상한. null 이면 폼 재고만 따른다 */
	@Column(name = "stock_max")
	private Integer stockMax;

	/** 결제 확정 전 선점 수량. 조건부 UPDATE 전용 — 읽기만 한다 */
	@Column(name = "held", nullable = false, insertable = false, updatable = false)
	private int held;

	/** 판매 확정 수량. 조건부 UPDATE 전용 — 읽기만 한다 */
	@Column(name = "sold", nullable = false, insertable = false, updatable = false)
	private int sold;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Builder
	private ProductOption(String name, int deposit1Amount, int deposit2Amount, Integer stockMax, int sortOrder) {
		this.name = name;
		this.deposit1Amount = deposit1Amount;
		this.deposit2Amount = deposit2Amount;
		this.stockMax = stockMax;
		this.sortOrder = sortOrder;
	}

	/** 옵션 총액 = 1차금 + 2차금 잔금. 배송비는 셀러 단위라 여기 포함하지 않는다 */
	public int totalAmount() {
		return deposit1Amount + deposit2Amount;
	}

	/** 옵션 자체 재고를 쓰는가 */
	public boolean hasStock() {
		return stockMax != null;
	}

	/** 옵션 재고에서 남은 수량. 상한이 없으면 null */
	public Integer remainingStock() {
		return (stockMax == null) ? null : stockMax - held - sold;
	}

	/** 이미 나간 수량. 옵션 재고를 이 아래로 줄이면 초과 판매가 된다 */
	public int committedQty() {
		return held + sold;
	}

	/**
	 * 지금 살 수 있는 수량. 폼 재고와 옵션 재고 중 작은 쪽이다.
	 *
	 * 구매자 화면의 스티퍼 상한과 장바구니 판정이 이 값을 쓴다. 옵션 재고가 있는 폼은
	 * 폼 재고가 옵션 합계라 보통 옵션 쪽이 작다.
	 */
	public int availableQty() {
		int formRemaining = product.getSaleForm().remainingStock();
		if (stockMax == null) {
			return formRemaining;
		}
		return Math.min(formRemaining, stockMax - held - sold);
	}

	/**
	 * 재고 상한을 바꾼다. 이미 나간 수량 밑으로는 못 내린다 — 호출자가 먼저 검사한다.
	 *
	 * @return 실제로 바뀌었으면 true
	 */
	public boolean changeStock(int newStockMax) {
		if (stockMax != null && stockMax == newStockMax) {
			return false;
		}
		this.stockMax = newStockMax;
		return true;
	}

	/** 연관관계 주인 쪽 설정. {@link Product#addOption} 을 통해서만 부른다 */
	void assignTo(Product product) {
		this.product = product;
	}
}
