package store.moeum.moeum.saleform.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

	/** 옵션 → 상품 → 판매 폼 → 셀러까지 한 번에. 장바구니 담기·주문 생성에서 매번 필요하다 */
	@Query("""
			select o from ProductOption o
			  join fetch o.product p
			  join fetch p.saleForm f
			  join fetch f.seller
			 where o.id = :optionId
			""")
	Optional<ProductOption> findWithFormById(Long optionId);

	@Query("""
			select o from ProductOption o
			  join fetch o.product p
			  join fetch p.saleForm f
			  join fetch f.seller
			 where o.id in :optionIds
			""")
	List<ProductOption> findAllWithFormByIdIn(List<Long> optionIds);

	// ---------------------------------------------------------------- 옵션 재고 (D-054)
	//
	// sale_form 의 네 쿼리와 짝이다. 폼 쿼리가 먼저 돌고, 같은 트랜잭션에서 그 폼의
	// 옵션을 id 오름차순으로 돈다. 판정과 갱신이 한 문장 안에서 끝나는 이유는
	// SaleFormRepository.hold 주석과 같다.

	/**
	 * 옵션 재고 확보. stock_max 가 NULL 이면 상한이 없으므로 조건 없이 held 만 올린다 —
	 * 그래야 나중에 재고를 넣어도 그때까지 나간 수량이 맞는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE product_option
			   SET held = held + :qty
			 WHERE id = :optionId
			   AND (stock_max IS NULL OR stock_max - held - sold >= :qty)
			""", nativeQuery = true)
	int hold(@Param("optionId") Long optionId, @Param("qty") int qty);

	/** 홀드 해제. held >= :qty 가 멱등 가드다 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE product_option
			   SET held = held - :qty
			 WHERE id = :optionId
			   AND held >= :qty
			""", nativeQuery = true)
	int releaseHold(@Param("optionId") Long optionId, @Param("qty") int qty);

	/** 홀드 확정 — held 에서 sold 로 옮긴다 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE product_option
			   SET held = held - :qty,
			       sold = sold + :qty
			 WHERE id = :optionId
			   AND held >= :qty
			""", nativeQuery = true)
	int commitHold(@Param("optionId") Long optionId, @Param("qty") int qty);

	/** 취소된 수량을 되돌린다. sold >= :qty 가 멱등 가드다 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE product_option
			   SET sold = sold - :qty
			 WHERE id = :optionId
			   AND sold >= :qty
			""", nativeQuery = true)
	int restoreSold(@Param("optionId") Long optionId, @Param("qty") int qty);
}
