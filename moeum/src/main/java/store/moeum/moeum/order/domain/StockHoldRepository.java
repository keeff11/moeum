package store.moeum.moeum.order.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockHoldRepository extends JpaRepository<StockHold, Long> {

	/**
	 * <b>판매 폼 id 순이다</b> (D-086). 호출자가 이 순서대로 재고 행(판매 폼 → 옵션)을 고친다.
	 * 주문 생성이 판매 폼을 id 오름차순으로 잠그므로, 홀드 id 순으로 고치면 두 홀드의 순서가 엇갈릴 때 교착이 난다.
	 */
	@Query("select h from StockHold h where h.order.id in :orderIds order by h.saleForm.id, h.id")
	List<StockHold> findByOrderIdIn(@Param("orderIds") List<Long> orderIds);

	/**
	 * 만료 배치와 겨루는 자리에서 쓴다 (D-063). 배치는 SKIP LOCKED 라 이쪽이 먼저 잡으면 건너뛰고,
	 * 배치가 먼저 잡았으면 기다렸다가 RELEASED 를 본다.
	 * 순서는 {@link #findByOrderIdIn} 과 같은 판매 폼 id 순이다 (D-086).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select h from StockHold h where h.order.id in :orderIds order by h.saleForm.id, h.id")
	List<StockHold> findByOrderIdInForUpdate(@Param("orderIds") List<Long> orderIds);

	/**
	 * 이탈 해제용 (D-088). 묶음을 잠근 뒤에 부른다.
	 *
	 * <b>남이 쥔 홀드는 기다리지 않고 건너뛴다.</b> 묶음 잠금을 쥔 채로 홀드를 기다리는 상대는 만료 배치뿐이다
	 * (결제 확정 · 교체는 묶음부터 잡아 여기까지 오지 못한다). 배치는 그 홀드를 이미 풀고 있고,
	 * 커밋하면서 묶음 행을 고치러 오므로 여기서 기다리면 서로 상대를 기다린다.
	 * 순서는 {@link #findByOrderIdIn} 과 같은 판매 폼 id 순이다 (D-086).
	 */
	@Query(value = """
			SELECT * FROM stock_hold
			 WHERE order_id IN (:orderIds)
			 ORDER BY sale_form_id, id
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<StockHold> findByOrderIdInForUpdateSkipLocked(@Param("orderIds") List<Long> orderIds);

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
