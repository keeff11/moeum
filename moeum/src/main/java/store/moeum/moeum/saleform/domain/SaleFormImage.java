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
 * 담는 값은 S3 객체 키다. 읽기용 주소는 {@code ImageStorage#publicUrl} 이 조립한다.
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

	/** S3 객체 키. 전체 URL 을 저장하지 않는다 (V4) — 버킷·CDN 이 바뀌면 조립하는 쪽만 고친다 */
	@Column(name = "object_key", nullable = false, length = 500)
	private String objectKey;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private SaleFormImage(String objectKey, int sortOrder) {
		this.objectKey = objectKey;
		this.sortOrder = sortOrder;
	}

	public static SaleFormImage of(String objectKey, int sortOrder) {
		return new SaleFormImage(objectKey, sortOrder);
	}

	/** 연관관계 주인 쪽 설정. {@link SaleForm#replaceImages} 를 통해서만 부른다 */
	void assignTo(SaleForm saleForm) {
		this.saleForm = saleForm;
	}
}
