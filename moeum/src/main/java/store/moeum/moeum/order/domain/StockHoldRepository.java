package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockHoldRepository extends JpaRepository<StockHold, Long> {

	List<StockHold> findByOrderIdIn(List<Long> orderIds);

	/**
	 * 만료 홀드 회수 대상.
	 *
	 * <b>CAPTURE_PENDING 인 묶음은 건너뛴다.</b> 승인 결과를 모르는 상태에서 재고를 남에게 넘기면,
	 * 승인이 뒤늦게 성공했을 때 초과 판매가 된다 (4단계에서 payment 가 생기면 이 조건이 살아난다).
	 *
	 * FOR UPDATE SKIP LOCKED 로 인스턴스 간에 나눠 집는다.
	 * 다른 인스턴스가 잡은 행은 기다리지 않고 건너뛴다.
	 * 잠금은 SQL 에 직접 적는다 — 네이티브 쿼리에는 @Lock 을 붙일 수 없다.
	 */
	@Query(value = """
			SELECT h.* FROM stock_hold h
			  JOIN orders o      ON o.id = h.order_id
			  JOIN order_group g ON g.id = o.order_group_id
			  LEFT JOIN payment p ON p.order_group_id = g.id AND p.status = 'CAPTURE_PENDING'
			 WHERE h.status = 'HELD'
			   AND h.expires_at < NOW(6)
			   AND p.id IS NULL
			 ORDER BY h.id
			 LIMIT :limit
			 FOR UPDATE OF h SKIP LOCKED
			""", nativeQuery = true)
	List<StockHold> findExpiredForUpdate(@Param("limit") int limit);

	long countByStatus(HoldStatus status);

	List<StockHold> findByStatusAndExpiresAtBefore(HoldStatus status, LocalDateTime time);
}
