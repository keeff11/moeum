package store.moeum.moeum.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import store.moeum.moeum.global.crypto.EncryptedStringConverter;

import java.time.LocalDateTime;

/**
 * 환불 계좌. 구매자당 하나다 (uk_refund_account_buyer) — 계좌부가 아니다.
 *
 * B5 1차금 결제 화면에서 배송지와 함께 받는 필수 항목이고, 판매 유형을 가리지 않는다
 * (D-057). 결제 수단으로 되돌리지 못하는 환불 — 정산이 끝난 뒤의 환불(S14 · B8-C3) —
 * 에서 돈을 보낼 곳이 여기밖에 없다.
 *
 * <b>{@link store.moeum.moeum.order.domain.Shipping} 같은 스냅샷을 만들지 않는다.</b>
 * 배송지는 "어디로 보냈는가"가 주문 시점으로 굳어야 분쟁의 근거가 되지만, 환불 계좌는
 * <b>돈을 보내는 시점에 살아 있는 계좌</b>여야 한다. 주문 당시 값을 굳혀 두면 몇 주 뒤의
 * 정산 후 환불에서 이미 해지된 계좌로 보내게 되고, 구매자가 고칠 방법이 없다.
 *
 * {@code accountNo} 는 DB 에 AES-256-GCM 으로 암호화되어 들어간다.
 * toString 을 만들지 않는다 — 로그에 실려 나가면 안 되는 값이다 (CLAUDE.md 규칙 10).
 */
@Entity
@Table(name = "buyer_refund_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BuyerRefundAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "buyer_id", nullable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_refund_account_buyer"))
	private Buyer buyer;

	/** 은행명. 화면의 선택지를 그대로 받는다 */
	@Column(name = "bank", nullable = false, length = 30)
	private String bank;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "account_no_enc", nullable = false, length = 255)
	private String accountNo;

	/** 예금주. 수령인 이름과 다를 수 있어 따로 받는다 */
	@Column(name = "holder_name", nullable = false, length = 50)
	private String holderName;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime updatedAt;

	@Builder
	private BuyerRefundAccount(Buyer buyer, String bank, String accountNo, String holderName) {
		this.buyer = buyer;
		this.bank = bank;
		this.accountNo = accountNo;
		this.holderName = holderName;
	}

	/** PUT 은 전체 교체다 */
	public void replaceWith(String bank, String accountNo, String holderName) {
		this.bank = bank;
		this.accountNo = accountNo;
		this.holderName = holderName;
	}

	/**
	 * 뒤 네 자리만 남기고 가린다 — {@code 1002-123-456789} → {@code ****6789}.
	 *
	 * <b>조회 응답에 나가는 값은 이것이다.</b> 본인의 계좌라도 전체 번호를 다시 내려보낼
	 * 이유가 없다 — 응답 본문에 남으면 개발자도구로 보이고 캐시에도 남는다
	 * ({@link store.moeum.moeum.order.domain.Shipping#maskedPhone()} 과 같은 취지).
	 * 번호를 바꿀 때는 새 번호를 다시 입력받는다.
	 */
	public String maskedAccountNo() {
		String digits = accountNo.replaceAll("\\D", "");

		if (digits.length() < 4) {
			return "****";
		}
		return "****" + digits.substring(digits.length() - 4);
	}
}
