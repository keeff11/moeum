package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

	/**
	 * 하트를 칠할 폼 id 목록.
	 *
	 * 카드 정보를 여기서 주지 않는다 — 셀러 페이지는 이미 목록을 받아 놓았고,
	 * 필요한 것은 어느 카드에 하트를 채울지뿐이다.
	 */
	@Query("select w.saleFormId from Wishlist w where w.buyerId = :buyerId order by w.id desc")
	List<Long> findSaleFormIds(@Param("buyerId") Long buyerId);

	/**
	 * 찜한다. <b>이미 찜한 상태여도 터지지 않는다.</b>
	 *
	 * 애플리케이션에서 먼저 확인하고 넣는 방식은 여기서 못 쓴다 —
	 * 유니크 위반이 나는 순간 트랜잭션이 rollback-only 로 표시돼서,
	 * 예외를 잡아도 커밋 시점에 UnexpectedRollbackException 으로 터진다.
	 *
	 * {@code ON DUPLICATE KEY UPDATE id = id} 는 중복만 삼킨다.
	 * {@code INSERT IGNORE} 와 달리 FK 위반은 그대로 올라온다 —
	 * 없는 구매자·폼으로 찜이 조용히 사라지면 안 된다.
	 *
	 * @return 새로 넣었으면 1, 이미 있었으면 0
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			INSERT INTO wishlist (buyer_id, sale_form_id, created_at)
			VALUES (:buyerId, :saleFormId, :now)
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	int insertIfAbsent(@Param("buyerId") Long buyerId,
	                   @Param("saleFormId") Long saleFormId,
	                   @Param("now") LocalDateTime now);

	/** 찜 해제. <b>멱등하다</b> — 없는 것을 지워도 0 을 돌려줄 뿐 터지지 않는다 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Wishlist w where w.buyerId = :buyerId and w.saleFormId = :saleFormId")
	int delete(@Param("buyerId") Long buyerId, @Param("saleFormId") Long saleFormId);
}
