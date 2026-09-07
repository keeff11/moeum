package store.moeum.moeum.payment.refund;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

	List<Refund> findByPaymentIdOrderByIdAsc(Long paymentId);

	/** 이 결제에 진행 중인 취소가 있는가. 있으면 새 취소를 만들지 않는다 */
	boolean existsByPaymentIdAndStatus(Long paymentId, RefundStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Refund r where r.id = :id")
	Optional<Refund> findByIdForUpdate(@Param("id") Long id);

	/** 이미 환불된 누적. 세금 안분의 기준이다 */
	@Query("""
			select new store.moeum.moeum.payment.refund.RefundedTotals(
			         coalesce(sum(r.amount), 0L),
			         coalesce(sum(r.vat), 0L),
			         coalesce(sum(r.taxFreeAmount), 0L))
			  from Refund r
			 where r.paymentId = :paymentId
			   and r.status = store.moeum.moeum.payment.refund.RefundStatus.COMPLETED
			""")
	RefundedTotals sumCompleted(@Param("paymentId") Long paymentId);

	/**
	 * 대사 배치가 집어갈 미확정 건.
	 *
	 * SKIP LOCKED 로 인스턴스 간 분산하고, 방금 만들어진 건은 제외한다 —
	 * 실시간 처리가 아직 응답을 기다리는 중일 수 있다.
	 */
	@Query(value = """
			SELECT * FROM refund
			 WHERE status = 'PROCESSING'
			   AND updated_at <= :threshold
			 ORDER BY updated_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Refund> findPendingForUpdate(@Param("threshold") LocalDateTime threshold,
	                                  @Param("limit") int limit);
}
