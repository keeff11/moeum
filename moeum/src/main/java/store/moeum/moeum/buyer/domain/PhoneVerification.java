package store.moeum.moeum.buyer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 발급한 인증번호 한 건 (D-064).
 *
 * <b>인증번호를 평문으로 들고 있지 않는다.</b> 번호와 이어 붙여 해시한 값만 둔다 —
 * 조회 권한만 있는 사람이 남의 번호를 바로 인증하는 것을 막는다.
 * toString 을 만들지 않는다.
 *
 * 구매자를 연관관계로 잡지 않고 id 만 둔다. 이 행은 발송 한도를 세는 데 주로 쓰이고,
 * 구매자를 따라 읽을 일이 없다.
 */
@Entity
@Table(name = "phone_verification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneVerification {

	/** 이만큼 틀리면 그 인증번호는 버린다. 6자리를 찍어 맞히는 것을 막는다 */
	public static final int MAX_ATTEMPTS = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "buyer_id", nullable = false, updatable = false)
	private Long buyerId;

	@Column(name = "phone", nullable = false, length = 20, updatable = false)
	private String phone;

	/** 길이가 늘 64 라 CHAR 다. 스키마 검증이 VARCHAR 를 기대하지 않게 못 박는다 */
	@Column(name = "code_hash", nullable = false, columnDefinition = "char(64)", updatable = false)
	private String codeHash;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "verified_at")
	private LocalDateTime verifiedAt;

	/** 발송 간격과 하루 한도를 이 값으로 센다. 시계를 주입받아야 해서 DB 기본값에 맡기지 않는다 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private PhoneVerification(Long buyerId, String phone, String code,
	                          LocalDateTime createdAt, LocalDateTime expiresAt) {
		this.buyerId = buyerId;
		this.phone = phone;
		this.codeHash = hash(phone, code);
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public static PhoneVerification issue(Long buyerId, String phone, String code,
	                                      LocalDateTime now, LocalDateTime expiresAt) {
		return new PhoneVerification(buyerId, phone, code, now, expiresAt);
	}

	public boolean isExpired(LocalDateTime now) {
		return !now.isBefore(expiresAt);
	}

	public boolean isExhausted() {
		return attempts >= MAX_ATTEMPTS;
	}

	public boolean isVerified() {
		return verifiedAt != null;
	}

	public int remainingAttempts() {
		return Math.max(0, MAX_ATTEMPTS - attempts);
	}

	/**
	 * 맞는지 본다. 틀리면 시도 횟수를 올린다.
	 *
	 * 비교는 상수 시간으로 한다 — 앞자리부터 맞춰 가며 응답 시간으로 추측하는 것을 막는다.
	 *
	 * @return 맞았으면 true
	 */
	public boolean tryMatch(String code, LocalDateTime now) {
		boolean matched = MessageDigest.isEqual(
				codeHash.getBytes(StandardCharsets.US_ASCII),
				hash(phone, code).getBytes(StandardCharsets.US_ASCII));

		if (matched) {
			this.verifiedAt = now;
		} else {
			this.attempts++;
		}
		return matched;
	}

	/** 인증번호가 같아도 번호가 다르면 다른 해시가 나오게 번호를 섞는다 */
	private static String hash(String phone, String code) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);

		} catch (NoSuchAlgorithmException e) {
			// JDK 표준 알고리즘이다. 여기 오면 런타임이 망가진 것이다
			throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
		}
	}
}
