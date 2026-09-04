package store.moeum.moeum.buyer.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 배송지. 구매자당 하나다 (uk_address_buyer) — 주소록이 아니다.
 * 묶음 배송이라 주문당 하나면 충분하고, 2차금 청구 때 재수집하지 않는다.
 *
 * 이 행은 마스터고, 주문 시점 값은 shipping 에 스냅샷으로 복사된다.
 */
@Entity
@Table(name = "buyer_address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerAddress {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buyer_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_address_buyer"))
	private Buyer buyer;

	/** 카카오 닉네임과 별개로 직접 입력받는다 */
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

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	@Builder
	private BuyerAddress(Buyer buyer, String recipientName, String phone, String postalCode,
	                     String address1, String address2, String memo) {
		this.buyer = buyer;
		this.recipientName = recipientName;
		this.phone = phone;
		this.postalCode = postalCode;
		this.address1 = address1;
		this.address2 = address2;
		this.memo = memo;
	}

	/** PUT 은 전체 교체다. 보내지 않은 선택 항목은 비워진다 */
	public void replaceWith(String recipientName, String phone, String postalCode,
	                        String address1, String address2, String memo) {
		this.recipientName = recipientName;
		this.phone = phone;
		this.postalCode = postalCode;
		this.address1 = address1;
		this.address2 = address2;
		this.memo = memo;
	}
}
