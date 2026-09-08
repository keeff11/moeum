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

	/** 목표수량 미달 처리를 끝낸 시각. null 이면 아직 안 훑었다 (V5) */
	@Column(name = "shortfall_done_at")
	private LocalDateTime shortfallDoneAt;

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

	/**
	 * 상품 이미지. 순서가 곧 노출 순서이고 첫 번째가 대표 이미지다.
	 *
	 * 상품·옵션과 달리 통째로 갈아끼운다. 주문 스냅샷이 이미지를 참조하지 않아
	 * 지난 주문과 어긋날 일이 없기 때문이다.
	 */
	@OneToMany(mappedBy = "saleForm", fetch = FetchType.LAZY,
			cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("sortOrder asc, id asc")
	private final List<SaleFormImage> images = new ArrayList<>();

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

	public List<SaleFormImage> getImages() {
		return Collections.unmodifiableList(images);
	}

	/** 노출 순서대로의 S3 객체 키 목록. 읽기용 주소는 ImageStorage 가 조립한다 */
	public List<String> imageKeys() {
		return images.stream().map(SaleFormImage::getObjectKey).toList();
	}

	/**
	 * 이미지를 통째로 교체한다. 넘긴 순서가 그대로 노출 순서가 되고, 값은 S3 객체 키다.
	 * null 을 넘기면 전부 비운다.
	 */
	public void replaceImages(List<String> objectKeys) {
		images.forEach(image -> image.assignTo(null));
		images.clear();
		if (objectKeys == null) {
			return;
		}
		for (int i = 0; i < objectKeys.size(); i++) {
			SaleFormImage image = SaleFormImage.of(objectKeys.get(i), i);
			image.assignTo(this);
			images.add(image);
		}
	}

	/**
	 * 판매를 시작한다. DRAFT 에서 처음 열거나, PAUSED 를 다시 여는 두 경우다.
	 *
	 * <b>CLOSED · ENDED 는 다시 열지 않는다.</b> 마감된 공구에는 이미 결제가 걸려 있고,
	 * 다시 열면 마감 이후 주문이 섞여 정산·발주 기준이 어긋난다. 새 폼을 만들어야 한다.
	 *
	 * @return 실제로 바뀌었으면 변경 이력, 이미 판매 중이면 null
	 */
	public FieldChange startSelling() {
		if (status == SaleFormStatus.SELLING) {
			return null;
		}
		if (status != SaleFormStatus.DRAFT && status != SaleFormStatus.PAUSED) {
			throw new IllegalStateException("판매를 시작할 수 없는 상태다: " + status);
		}
		SaleFormStatus before = status;
		this.status = SaleFormStatus.SELLING;
		return new FieldChange("status", before, SaleFormStatus.SELLING);
	}

	/**
	 * 일시중지. 구매 버튼만 막고 마감은 아니다 — 이미 잡힌 홀드와 결제는 그대로 흘러간다.
	 *
	 * 홀드를 여기서 풀지 않는다. 결제 중인 구매자를 중간에 끊으면
	 * 승인은 나가고 재고는 없는 상태가 된다.
	 */
	public FieldChange pause() {
		if (status == SaleFormStatus.PAUSED) {
			return null;
		}
		if (status != SaleFormStatus.SELLING) {
			throw new IllegalStateException("일시중지할 수 없는 상태다: " + status);
		}
		this.status = SaleFormStatus.PAUSED;
		return new FieldChange("status", SaleFormStatus.SELLING, SaleFormStatus.PAUSED);
	}

	/**
	 * 수동 마감. 마감 시각을 기다리지 않고 셀러가 직접 닫는다.
	 *
	 * 되돌릴 수 없다 — {@link #startSelling()} 이 CLOSED 를 거부한다.
	 */
	public FieldChange close() {
		if (status == SaleFormStatus.CLOSED) {
			return null;
		}
		if (status != SaleFormStatus.SELLING && status != SaleFormStatus.PAUSED) {
			throw new IllegalStateException("마감할 수 없는 상태다: " + status);
		}
		SaleFormStatus before = status;
		this.status = SaleFormStatus.CLOSED;
		return new FieldChange("status", before, SaleFormStatus.CLOSED);
	}

	/** 판매를 열 수 있는 상태인가. CLOSED · ENDED 는 되돌릴 수 없다 */
	public boolean isStartable() {
		return status == SaleFormStatus.DRAFT || status == SaleFormStatus.PAUSED
				|| status == SaleFormStatus.SELLING;
	}

	// ---------------------------------------------------------------- 목표수량 미달

	/**
	 * 목표수량에 못 미친 채 마감됐는가.
	 *
	 * <b>기준은 {@code sold} 다.</b> 마감 전 취소는 {@code sold} 에서 빠지므로
	 * 마감 시점의 {@code sold} 가 곧 실제 결제자 수다 (D-024). {@code held} 는 세지 않는다 —
	 * 결제되지 않은 홀드는 곧 만료된다.
	 *
	 * SOLO 와 목표수량이 없는 폼은 미달이라는 개념 자체가 없다.
	 */
	public boolean isShortfall() {
		return saleType == SaleType.GROUP && targetQty != null && sold < targetQty;
	}

	/** 이 폼의 미달 처리를 끝냈다고 표시한다. 정책과 무관하게 한 번 훑으면 찍는다 */
	public void markShortfallDone(LocalDateTime at) {
		this.shortfallDoneAt = at;
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
		List<String> newImages = (update.images() == null) ? List.of() : update.images();
		record(changes, "images", String.join(",", imageKeys()), String.join(",", newImages));

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
		replaceImages(newImages);

		return changes;
	}

	private static void record(List<FieldChange> changes, String field, Object oldValue, Object newValue) {
		if (!Objects.equals(oldValue, newValue)) {
			changes.add(new FieldChange(field, oldValue, newValue));
		}
	}
}
