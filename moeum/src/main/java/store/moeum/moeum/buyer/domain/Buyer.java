package store.moeum.moeum.buyer.domain;

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
 * 구매자. 카카오 계정 하나당 하나다.
 *
 * payerId 는 point3 가 준 결제자 식별값이다. 2차금 결제에서 인증 단계를 줄이는 데 쓴다.
 * 받은 문자열을 그대로 보관하고, 로그에는 남기지 않는다.
 */
@Entity
@Table(name = "buyer")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Buyer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "kakao_id", nullable = false, length = 64, updatable = false)
	private String kakaoId;

	/** 카카오 프로필. 수령인 이름과는 별개다 */
	@Column(name = "nickname", length = 50)
	private String nickname;

	@Column(name = "payer_id", length = 64)
	private String payerId;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Buyer(String kakaoId, String nickname) {
		this.kakaoId = kakaoId;
		this.nickname = nickname;
	}

	public static Buyer of(String kakaoId, String nickname) {
		return new Buyer(kakaoId, nickname);
	}

	/** 카카오 닉네임은 바뀔 수 있다. 로그인할 때마다 맞춰 둔다 */
	public void updateNickname(String nickname) {
		this.nickname = nickname;
	}

	/**
	 * 1차금 결제에서 point3 가 준 값을 그대로 보관한다. 2차금 때 인증 단계를 줄이는 데 쓴다.
	 *
	 * <b>이미 값이 있으면 덮어쓰지 않는다</b> (point3-api 5절).
	 * point3 가 관리하는 값이라 우리가 판단해 갈아끼울 근거가 없고,
	 * 접두사를 떼거나 대소문자를 바꾸면 인증 생략 자체가 동작하지 않는다.
	 *
	 * @return 이번 호출로 저장됐으면 true
	 */
	public boolean rememberPayerId(String payerId) {
		if (payerId == null || payerId.isBlank() || this.payerId != null) {
			return false;
		}
		this.payerId = payerId;
		return true;
	}

	public boolean hasPayerId() {
		return payerId != null && !payerId.isBlank();
	}
}
