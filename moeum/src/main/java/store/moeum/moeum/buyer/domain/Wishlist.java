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

import java.time.LocalDateTime;

/**
 * 찜한 판매 폼 한 건 (V8).
 *
 * 연관관계 대신 id 를 그대로 든다. 찜은 붙였다 뗐다 하는 값이라
 * 구매자·폼 엔티티를 끌고 올 이유가 없고, 목록 조회도 id 만 내보낸다.
 */
@Entity
@Table(name = "wishlist")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "buyer_id", nullable = false, updatable = false)
	private Long buyerId;

	@Column(name = "sale_form_id", nullable = false, updatable = false)
	private Long saleFormId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Wishlist(Long buyerId, Long saleFormId, LocalDateTime now) {
		this.buyerId = buyerId;
		this.saleFormId = saleFormId;
		this.createdAt = now;
	}

	public static Wishlist of(Long buyerId, Long saleFormId, LocalDateTime now) {
		return new Wishlist(buyerId, saleFormId, now);
	}
}
