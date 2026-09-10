package store.moeum.moeum.saleform.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
	 * 셀러 홈(G1)의 진행 중 판매 카드.
	 *
	 * <b>SELLING 과 PAUSED 를 같이 본다.</b> 일시중지는 끝난 판매가 아니라 셀러가 손을
	 * 대야 하는 판매다 — 목록에서 빼면 멈춰 둔 것을 잊는다. 상태는 응답에 실어 배지로 구분한다.
	 *
	 * <b>마감이 임박한 것이 위로 온다.</b> D-day 가 이 카드의 존재 이유라서 그렇다.
	 * 마감일이 없는 단독 판매는 급할 것이 없으니 뒤로 보낸다 —
	 * MySQL 은 NULL 을 가장 작게 보므로 {@code closes_at IS NULL} 을 먼저 정렬한다.
	 *
	 * 네이티브인 이유는 그 NULLS LAST 때문이다. JPQL 에는 표준 문법이 없다.
	 */
	@Query(value = """
			SELECT f.* FROM sale_form f
			 WHERE f.seller_id = :sellerId
			   AND f.status IN ('SELLING', 'PAUSED')
			 ORDER BY (f.closes_at IS NULL), f.closes_at ASC, f.id DESC
			 LIMIT :limit
			""", nativeQuery = true)
	List<SaleForm> findSellerActiveForms(@Param("sellerId") Long sellerId, @Param("limit") int limit);

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
	 * 셀러 페이지(B0) 목록. 검색어와 판매 유형으로 거른다.
	 *
	 * <b>DRAFT 는 빠진다.</b> 미발행 폼은 셀러 본인 화면에만 보인다.
	 *
	 * 정렬은 판매 중이 먼저, 그다음 최신순이다 — 마감된 폼이 위에 쌓이면
	 * 지금 살 수 있는 것을 찾으려고 스크롤해야 한다.
	 *
	 * 네이티브로 쓴 이유는 <b>선택적 필터의 null 처리</b> 때문이다. JPQL 에서
	 * {@code :saleType is null} 을 enum 파라미터로 쓰면 타입 추론이 걸린다.
	 * 문자열로 받아 비교하면 그 문제가 없다.
	 *
	 * fetch join 을 걸지 않는다 — 컬렉션을 조인하면 LIMIT 이 행 기준으로 잘려
	 * 페이지 크기가 틀어진다. products · images 는 default_batch_fetch_size 가 끌어온다.
	 */
	@Query(value = """
			SELECT f.* FROM sale_form f
			 WHERE f.seller_id = :sellerId
			   AND f.status <> 'DRAFT'
			   AND (:saleType IS NULL OR f.sale_type = :saleType)
			   AND (:keyword IS NULL OR f.title LIKE :keyword ESCAPE '!')
			 ORDER BY (f.status = 'SELLING') DESC, f.id DESC
			""",
			countQuery = """
			SELECT COUNT(*) FROM sale_form f
			 WHERE f.seller_id = :sellerId
			   AND f.status <> 'DRAFT'
			   AND (:saleType IS NULL OR f.sale_type = :saleType)
			   AND (:keyword IS NULL OR f.title LIKE :keyword ESCAPE '!')
			""",
			nativeQuery = true)
	Page<SaleForm> findStorePage(@Param("sellerId") Long sellerId,
	                             @Param("saleType") String saleType,
	                             @Param("keyword") String keyword,
	                             Pageable pageable);

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

	/**
	 * 목표수량 미달 처리를 아직 안 훑은 마감 폼 (D-026).
	 *
	 * <b>SKIP LOCKED 로 인스턴스 간 분산한다.</b> 미달 취소는 이미 결제된 돈을 돌려주는 일이라
	 * 두 인스턴스가 같은 폼을 집으면 같은 주문에 취소를 두 번 보내게 된다.
	 *
	 * CLOSED 만 본다 — SELLING 중에는 아직 미달인지 판단할 수 없고, 마감 배치가 먼저 CLOSED 로 넘긴다.
	 */
	@Query(value = """
			SELECT * FROM sale_form
			 WHERE status = 'CLOSED'
			   AND shortfall_done_at IS NULL
			 ORDER BY id
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<SaleForm> findClosedForShortfall(@Param("limit") int limit);

	/**
	 * 취소된 수량을 재고로 되돌린다 (D-024).
	 *
	 * {@code sold >= :qty} 조건이 멱등 가드다. 같은 취소를 두 번 확정해도 sold 가 음수로 내려가지 않는다.
	 * 되돌릴지 말지(SOLO / GROUP 마감 여부)는 호출자가 판단한다 — 여기서는 시키는 대로만 한다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE sale_form
			   SET sold = sold - :qty
			 WHERE id = :formId
			   AND sold >= :qty
			""", nativeQuery = true)
	int restoreSold(@Param("formId") Long formId, @Param("qty") int qty);
}
