package store.moeum.moeum.seller.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.global.crypto.EncryptedStringConverter;
import store.moeum.moeum.global.jpa.BaseTimeEntity;
import store.moeum.moeum.global.jpa.JpaAuditingConfig;

import java.time.LocalDateTime;

/**
 * 셀러. 카카오 계정으로 가입하고 심사를 통과해야 판매할 수 있다.
 *
 * businessNo · settlementAccount 는 DB 에 AES-256-GCM 으로 암호화되어 들어간다.
 * toString 을 만들지 않는다 — 로그에 실려 나가면 안 되는 값이다.
 */
@Entity
@Table(name = "seller")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	/** 카카오 회원번호. 가입 후 바뀌지 않는다 */
	@Column(name = "kakao_id", nullable = false, length = 64, updatable = false)
	private String kakaoId;

	/** 판매공간 URL 식별자 */
	@Column(name = "store_slug", nullable = false, length = 64)
	private String storeSlug;

	/**
	 * 공개 상품 페이지에 표시할 상호명.
	 *
	 * representativeName(대표자 실명)을 여기 쓰지 않는다 — 개인정보다.
	 * 이 컬럼이 생기기 전에 가입한 셀러는 비어 있을 수 있어 {@link #displayName()} 이 대체값을 준다.
	 */
	@Column(name = "store_name", length = 60)
	private String storeName;

	@Enumerated(EnumType.STRING)
	@Column(name = "review_status", nullable = false, length = 20)
	private ReviewStatus reviewStatus;

	/** 주문 묶음당 1회 부과. 배송비의 주체는 판매 폼이 아니라 셀러다 */
	@Column(name = "shipping_fee", nullable = false)
	private int shippingFee;

	/** 이 금액 이상이면 무료배송. null 이면 미적용 */
	@Column(name = "free_shipping_over")
	private Integer freeShippingOver;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "business_no_enc", length = 255)
	private String businessNo;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "settlement_acct_enc", length = 255)
	private String settlementAccount;

	@Column(name = "representative_name", length = 50)
	private String representativeName;

	@Column(name = "phone", length = 20)
	private String phone;

	@Column(name = "email", length = 120)
	private String email;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Builder
	private Seller(String kakaoId, String storeSlug, String storeName, int shippingFee, Integer freeShippingOver,
	               String businessNo, String settlementAccount,
	               String representativeName, String phone, String email) {
		this.kakaoId = kakaoId;
		this.storeSlug = storeSlug;
		this.storeName = storeName;
		this.reviewStatus = ReviewStatus.PENDING;
		this.shippingFee = shippingFee;
		this.freeShippingOver = freeShippingOver;
		this.businessNo = businessNo;
		this.settlementAccount = settlementAccount;
		this.representativeName = representativeName;
		this.phone = phone;
		this.email = email;
	}

	public void approve() {
		this.reviewStatus = ReviewStatus.APPROVED;
		this.approvedAt = LocalDateTime.now(JpaAuditingConfig.KST);
	}

	public void reject() {
		this.reviewStatus = ReviewStatus.REJECTED;
		this.approvedAt = null;
	}

	public boolean isApproved() {
		return reviewStatus == ReviewStatus.APPROVED;
	}

	/**
	 * 공개 페이지에 내보낼 이름.
	 *
	 * store_name 컬럼(V3)이 생기기 전에 가입한 셀러는 값이 없다. 그때는 store_slug 로 대체한다 —
	 * 대표자 실명으로 대체하지 않는다. 이름이 비었다고 개인정보를 공개할 이유는 없다.
	 */
	public String displayName() {
		return (storeName == null || storeName.isBlank()) ? storeSlug : storeName;
	}

	/** 주문 묶음 금액 기준 배송비. 무료 기준을 넘으면 0 */
	public int shippingFeeFor(int orderAmount) {
		if (freeShippingOver != null && orderAmount >= freeShippingOver) {
			return 0;
		}
		return shippingFee;
	}
}
