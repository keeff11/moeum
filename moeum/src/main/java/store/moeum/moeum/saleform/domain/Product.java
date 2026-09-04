package store.moeum.moeum.saleform.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 판매 폼에 속한 상품. product 테이블에는 updated_at 이 없어
 * BaseTimeEntity 를 쓰지 않고 created_at 만 둔다.
 */
@Entity
@Table(name = "product")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_form_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_product_form"))
	private SaleForm saleForm;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/** 옵션은 상품과 생명주기를 같이하고 항상 함께 다뤄지므로 양방향으로 둔다 */
	@OneToMany(mappedBy = "product", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("sortOrder asc, id asc")
	private final List<ProductOption> options = new ArrayList<>();

	@Builder
	private Product(String name, int sortOrder) {
		this.name = name;
		this.sortOrder = sortOrder;
	}

	public List<ProductOption> getOptions() {
		return Collections.unmodifiableList(options);
	}

	public void addOption(ProductOption option) {
		options.add(option);
		option.assignTo(this);
	}

	public void removeOption(ProductOption option) {
		options.remove(option);
		option.assignTo(null);
	}

	/** 연관관계 주인 쪽 설정. {@link SaleForm#addProduct} 를 통해서만 부른다 */
	void assignTo(SaleForm saleForm) {
		this.saleForm = saleForm;
	}
}
