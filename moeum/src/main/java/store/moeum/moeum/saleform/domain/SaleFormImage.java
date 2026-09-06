package store.moeum.moeum.saleform.domain;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 상품 이미지. 순서가 곧 노출 순서이고 첫 번째가 대표 이미지다.
 *
 * 파일 업로드 인프라가 없어 지금은 셀러가 외부 URL 을 입력한다.
 * 업로드가 붙어도 이 값을 채우는 주체만 바뀐다.
 */
@Entity
@Table(name = "sale_form_image")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleFormImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_form_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_sale_form_image_form"))
	private SaleForm saleForm;

	@Column(name = "url", nullable = false, length = 500)
	private String url;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private SaleFormImage(String url, int sortOrder) {
		this.url = url;
		this.sortOrder = sortOrder;
	}

	public static SaleFormImage of(String url, int sortOrder) {
		return new SaleFormImage(url, sortOrder);
	}

	/** 연관관계 주인 쪽 설정. {@link SaleForm#replaceImages} 를 통해서만 부른다 */
	void assignTo(SaleForm saleForm) {
		this.saleForm = saleForm;
	}
}
