package store.moeum.moeum.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import store.moeum.moeum.buyer.domain.BuyerAddress;

import java.time.LocalDateTime;

/**
 * 배송지 스냅샷. 묶음당 1건이다 (uk_shipping_group).
 *
 * <b>buyer_address 를 참조하지 않고 값을 복사해 온다.</b> buyer_address 는 마스터라
 * 구매자가 이사하면 통째로 교체된다({@link BuyerAddress#replaceWith}). 참조로 두면
 * 이미 발송된 주문의 "어디로 보냈는가"가 같이 바뀌어, 분쟁이 났을 때 근거가 없다.
 *
 * 복사 시점은 /pay 다 — order_token · order_no 와 같은 자리다 (D-033).
 * CREATED 세션은 15분 뒤 사라지는 임시 자리라 배송지를 굳힐 이유가 없다.
 *
 * carrier · trackingNo · shippedAt 은 지금 채우지 않는다. 송장 등록(7단계)이 쓸 자리다.
 */
@Entity
@Table(name = "shipping")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_group_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_shipping_group"))
	private OrderGroup orderGroup;

	/** 카카오 닉네임이 아니라 구매자가 배송지에 직접 적은 수령인이다 */
	@Column(name = "recipient_name", nullable = false, length = 50)
	private String recipientName;

	@Column(name = "phone", nullable = false, length = 20)
	private String phone;

	@Column(name = "postal_code", length = 10)
	private String postalCode;

	@Column(name = "address1", nullable = false, length = 255)
	private String address1;

	@Column(name = "address2", length = 255)
	private String address2;

	@Column(name = "memo", length = 200)
	private String memo;

	/** 송장 3종. 7단계 송장 등록 전까지는 비어 있다 */
	@Column(name = "carrier", length = 50)
	private String carrier;

	@Column(name = "tracking_no", length = 50)
	private String trackingNo;

	@Column(name = "shipped_at")
	private LocalDateTime shippedAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	private Shipping(OrderGroup orderGroup, BuyerAddress address) {
		this.orderGroup = orderGroup;
		this.recipientName = address.getRecipientName();
		this.phone = address.getPhone();
		this.postalCode = address.getPostalCode();
		this.address1 = address.getAddress1();
		this.address2 = address.getAddress2();
		this.memo = address.getMemo();
	}

	/** 주문 시점의 배송지를 그대로 떠 온다 */
	public static Shipping snapshotOf(OrderGroup orderGroup, BuyerAddress address) {
		return new Shipping(orderGroup, address);
	}

	/**
	 * 전화번호를 가운데만 가린다 — {@code 010-1234-5678} → {@code 010-****-5678}.
	 *
	 * <b>셀러 화면에 내려보내는 값은 이것이다.</b> 원본을 내려주고 프론트가 가리면
	 * 응답 본문에는 그대로 남아 개발자도구로 보인다 (CLAUDE.md 규칙 10 과 같은 취지).
	 *
	 * 입력 형식을 강제하지 않는다 — 하이픈을 넣는 사람도 안 넣는 사람도 있다.
	 * 숫자만 뽑아 앞 세 자리와 뒤 네 자리만 남기고, 그만큼도 안 되면 전부 가린다.
	 */
	public String maskedPhone() {
		String digits = phone.replaceAll("\\D", "");

		if (digits.length() < 7) {
			return "****";
		}
		return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
	}
}
