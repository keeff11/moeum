package store.moeum.moeum.saleform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 판매 폼 변경 이력. 바뀐 필드마다 한 행이다.
 *
 * 판매 폼은 구매자에게 이미 링크가 나간 뒤에도 셀러가 고칠 수 있다.
 * "마감일이 원래 언제였는지", "가격을 올린 적 있는지"를 나중에 따질 수 있어야 해서 남긴다.
 *
 * sale_form 을 참조하지만 연관관계로 매핑하지 않는다. 이력은 폼을 따라다니는 부속이 아니라
 * 조회 전용 기록이고, 폼을 로딩할 때 딸려 올라올 이유가 없다.
 */
@Entity
@Table(name = "sale_form_history")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleFormHistory {

	/** old_value · new_value 컬럼 길이 */
	private static final int VALUE_MAX = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "sale_form_id", nullable = false, updatable = false)
	private Long saleFormId;

	@Column(name = "field", nullable = false, length = 50, updatable = false)
	private String field;

	@Column(name = "old_value", length = VALUE_MAX, updatable = false)
	private String oldValue;

	@Column(name = "new_value", length = VALUE_MAX, updatable = false)
	private String newValue;

	/** seller.id */
	@Column(name = "changed_by", updatable = false)
	private Long changedBy;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private SaleFormHistory(Long saleFormId, String field, String oldValue, String newValue, Long changedBy) {
		this.saleFormId = saleFormId;
		this.field = field;
		this.oldValue = truncate(oldValue);
		this.newValue = truncate(newValue);
		this.changedBy = changedBy;
	}

	public static SaleFormHistory of(Long saleFormId, String field, Object oldValue, Object newValue, Long changedBy) {
		return new SaleFormHistory(saleFormId, field, stringify(oldValue), stringify(newValue), changedBy);
	}

	private static String stringify(Object value) {
		return (value == null) ? null : String.valueOf(value);
	}

	/** descriptionJson 처럼 긴 값이 들어와도 이력 기록 때문에 수정이 실패하면 안 된다 */
	private static String truncate(String value) {
		if (value == null || value.length() <= VALUE_MAX) {
			return value;
		}
		return value.substring(0, VALUE_MAX - 3) + "...";
	}
}
