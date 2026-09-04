package store.moeum.moeum.saleform.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 판매 폼. 재고 · 목표수량 · 마감 · 2차금 정책의 주체다.
 *
 * <b>held / sold 는 이 클래스로 바꾸지 않는다.</b>
 * 재고 확보는 3단계에서 조건부 UPDATE 한 방으로 처리한다. SELECT 후 필드를 고쳐 flush 하는 방식은
 * 두 요청이 같은 값을 읽고 각자 더하는 순간 초과 판매가 된다. 그래서 두 컬럼은
 * 읽기 전용(insertable=false, updatable=false)으로 막아 두고 setter 도 두지 않았다.
 *
 * {@code @DynamicUpdate} 를 건 이유도 같다. 다른 필드 하나를 고쳐 flush 할 때 Hibernate 가
 * 전체 컬럼을 쓰면, 메모리에 들고 있던 낡은 held/sold 가 네이티브 쿼리 결과를 덮어쓴다.
 */
@Entity
@Table(name = "sale_form")
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SaleForm extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "seller_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_sale_form_seller"))
	private Seller seller;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Column(name = "slug", nullable = false, length = 120)
	private String slug;

	@Enumerated(EnumType.STRING)
	@Column(name = "sale_type", nullable = false, length = 10, updatable = false)
	private SaleType saleType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SaleFormStatus status;

	@Column(name = "stock_max", nullable = false)
	private int stockMax;

	/** 결제 확정 전 선점 수량. 3단계 조건부 UPDATE 전용 — 읽기만 한다 */
	@Column(name = "held", nullable = false, insertable = false, updatable = false)
	private int held;

	/** 판매 확정 수량. 3단계 조건부 UPDATE 전용 — 읽기만 한다 */
	@Column(name = "sold", nullable = false, insertable = false, updatable = false)
	private int sold;

	/** 목표수량(최소). SOLO 는 null */
	@Column(name = "target_qty")
	private Integer targetQty;

	/** 1인당 구매 상한. null 이면 무제한 */
	@Column(name = "max_per_user")
	private Integer maxPerUser;

	@Column(name = "opens_at")
	private LocalDateTime opensAt;

	@Column(name = "closes_at")
	private LocalDateTime closesAt;

	@Column(name = "extended_count", nullable = false)
	private int extendedCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "shortfall_policy", length = 10)
	private ShortfallPolicy shortfallPolicy;

	/** 발송 시작 안내 문구. 서버가 포맷해 내려준다 */
	@Column(name = "ship_start_text", length = 100)
	private String shipStartText;

	@Column(name = "min_order_amount", nullable = false)
	private int minOrderAmount;

	/** 상세 설명 Lexical JSON (ADR 0001) */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "description_json")
	private String descriptionJson;

	@Column(name = "progress_public", nullable = false)
	private boolean progressPublic;

	/**
	 * 상품은 판매 폼과 생명주기를 같이한다. 폼 생성 시 상품 · 옵션을 함께 받고
	 * 상세 조회에서 함께 보여주므로 양방향으로 둔다.
	 */
	@OneToMany(mappedBy = "saleForm", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("sortOrder asc, id asc")
	private final List<Product> products = new ArrayList<>();

	@Builder
	private SaleForm(Seller seller, String title, String slug, SaleType saleType, int stockMax,
	                 Integer targetQty, Integer maxPerUser, LocalDateTime opensAt, LocalDateTime closesAt,
	                 ShortfallPolicy shortfallPolicy, String shipStartText, int minOrderAmount,
	                 String descriptionJson, Boolean progressPublic) {
		this.seller = seller;
		this.title = title;
		this.slug = slug;
		this.saleType = saleType;
		this.status = SaleFormStatus.DRAFT;
		this.stockMax = stockMax;
		this.targetQty = targetQty;
		this.maxPerUser = maxPerUser;
		this.opensAt = opensAt;
		this.closesAt = closesAt;
		this.extendedCount = 0;
		this.shortfallPolicy = shortfallPolicy;
		this.shipStartText = shipStartText;
		this.minOrderAmount = minOrderAmount;
		this.descriptionJson = descriptionJson;
		this.progressPublic = (progressPublic == null) || progressPublic;
	}

	public List<Product> getProducts() {
		return Collections.unmodifiableList(products);
	}

	public void addProduct(Product product) {
		products.add(product);
		product.assignTo(this);
	}

	public void removeProduct(Product product) {
		products.remove(product);
		product.assignTo(null);
	}

	/** 남은 수량. held · sold 는 DB 값이므로 조회 시점 기준이다 */
	public int remainingStock() {
		return stockMax - held - sold;
	}

	/** 이미 나간 수량. 재고를 이 아래로 줄이면 초과 판매가 된다 */
	public int committedQty() {
		return held + sold;
	}

	/**
	 * 수정 가능한 필드만 반영하고, 실제로 바뀐 것들을 돌려준다.
	 * 호출자가 그 목록으로 sale_form_history 를 남긴다.
	 *
	 * 값이 같으면 변경으로 치지 않는다 — 저장 버튼만 눌러도 이력이 쌓이면 이력을 볼 이유가 없어진다.
	 */
	public List<FieldChange> update(SaleFormUpdate update) {
		List<FieldChange> changes = new ArrayList<>();

		boolean group = (saleType == SaleType.GROUP);
		Integer newTargetQty = group ? update.targetQty() : null;
		ShortfallPolicy newShortfallPolicy = group ? update.shortfallPolicy() : null;
		boolean newProgressPublic = (update.progressPublic() == null) || update.progressPublic();

		record(changes, "title", title, update.title());
		record(changes, "stockMax", stockMax, update.stockMax());
		record(changes, "targetQty", targetQty, newTargetQty);
		record(changes, "maxPerUser", maxPerUser, update.maxPerUser());
		record(changes, "opensAt", opensAt, update.opensAt());
		record(changes, "closesAt", closesAt, update.closesAt());
		record(changes, "shortfallPolicy", shortfallPolicy, newShortfallPolicy);
		record(changes, "shipStartText", shipStartText, update.shipStartText());
		record(changes, "minOrderAmount", minOrderAmount, update.minOrderAmount());
		record(changes, "descriptionJson", descriptionJson, update.descriptionJson());
		record(changes, "progressPublic", progressPublic, newProgressPublic);

		this.title = update.title();
		this.stockMax = update.stockMax();
		this.targetQty = newTargetQty;
		this.maxPerUser = update.maxPerUser();
		this.opensAt = update.opensAt();
		this.closesAt = update.closesAt();
		this.shortfallPolicy = newShortfallPolicy;
		this.shipStartText = update.shipStartText();
		this.minOrderAmount = update.minOrderAmount();
		this.descriptionJson = update.descriptionJson();
		this.progressPublic = newProgressPublic;

		return changes;
	}

	private static void record(List<FieldChange> changes, String field, Object oldValue, Object newValue) {
		if (!Objects.equals(oldValue, newValue)) {
			changes.add(new FieldChange(field, oldValue, newValue));
		}
	}
}
