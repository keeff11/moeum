package store.moeum.moeum.saleform.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SaleFormRepository extends JpaRepository<SaleForm, Long> {

	List<SaleForm> findBySellerIdOrderByIdDesc(Long sellerId);

	boolean existsBySellerIdAndSlug(Long sellerId, String slug);

	/**
	 * 상세 조회. products 만 fetch join 한다.
	 * options 까지 같이 join 하면 컬렉션 두 개를 동시에 fetch 하게 되어 Hibernate 가 거부한다
	 * (MultipleBagFetchException). options 는 default_batch_fetch_size 로 한 번에 끌어온다.
	 */
	@Query("select f from SaleForm f left join fetch f.products where f.id = :id")
	Optional<SaleForm> findDetailById(Long id);

	/**
	 * 재고 확보. <b>조건부 UPDATE 한 방이다.</b> 영향 행 0이면 품절 또는 마감이다.
	 *
	 * SELECT 로 남은 재고를 읽고 애플리케이션에서 판단한 뒤 UPDATE 하면 안 된다.
	 * 두 요청이 같은 값(99)을 읽고 각자 +1 하면 재고 100 인데 101 개가 팔린다.
	 * 조건을 WHERE 에 두면 판정과 갱신이 한 문장 안에서 원자적으로 끝난다.
	 *
	 * NOW(6) 은 DB 시각이다. 컨테이너와 커넥션을 Asia/Seoul 로 맞춰 뒀다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE sale_form
			   SET held = held + :qty
			 WHERE id = :formId
			   AND status = 'SELLING'
			   AND stock_max - held - sold >= :qty
			   AND (closes_at IS NULL OR closes_at > NOW(6))
			""", nativeQuery = true)
	int hold(@Param("formId") Long formId, @Param("qty") int qty);

	/**
	 * 홀드 해제 — 재고를 되돌린다. 이탈 · 만료 · 결제 실패에서 쓴다.
	 *
	 * held >= :qty 조건이 멱등 가드다. 배치가 같은 건을 두 번 처리해도 held 가 음수로 내려가지 않는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE sale_form
			   SET held = held - :qty
			 WHERE id = :formId
			   AND held >= :qty
			""", nativeQuery = true)
	int releaseHold(@Param("formId") Long formId, @Param("qty") int qty);

	/** 홀드 확정 — held 에서 sold 로 옮긴다. 승인 완료(captured) 후에만 부른다 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE sale_form
			   SET held = held - :qty,
			       sold = sold + :qty
			 WHERE id = :formId
			   AND held >= :qty
			""", nativeQuery = true)
	int commitHold(@Param("formId") Long formId, @Param("qty") int qty);
}
