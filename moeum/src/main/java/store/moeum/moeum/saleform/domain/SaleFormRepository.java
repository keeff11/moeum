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
	 * 구매자용 공개 상세 조회. 셀러까지 한 번에 끌어온다.
	 *
	 * 셀러 이름·배송비가 응답에 들어가는데, LAZY 로 두면 상품 하나 조회에 쿼리가 두 번 나간다.
	 * 셀러는 ManyToOne 이라 컬렉션이 아니고, 그래서 products 와 같이 fetch 해도 문제되지 않는다.
	 * images 는 두 번째 컬렉션이라 여기 넣지 않는다 — default_batch_fetch_size 가 한 번에 끌어온다.
	 */
	@Query("select f from SaleForm f join fetch f.seller left join fetch f.products where f.id = :id")
	Optional<SaleForm> findPublicDetailById(@Param("id") Long id);

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

	/**
	 * 마감 시각이 지난 판매 중인 폼을 CLOSED 로 넘긴다. 공구 마감 배치가 부른다.
	 *
	 * <b>shortfall_policy 는 여기서 적용하지 않는다.</b> 목표수량 미달 시 CANCEL 은
	 * 이미 1차금이 결제된 주문을 환불한다는 뜻이고, 결제·환불이 아직 없다 (6단계).
	 * 지금 하는 일은 상태 전이 하나뿐이다.
	 *
	 * 엔티티를 올리지 않고 한 문장으로 끝낸다 — 수백 건이 한꺼번에 마감돼도 부담이 없고,
	 * held/sold 를 메모리에 들고 있다 덮어쓸 위험도 없다.
	 *
	 * NOW(6) 은 DB 시각이다. hold 쿼리와 같은 기준을 쓴다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE sale_form
			   SET status = 'CLOSED'
			 WHERE status = 'SELLING'
			   AND closes_at IS NOT NULL
			   AND closes_at <= NOW(6)
			""", nativeQuery = true)
	int closeExpired();
}
