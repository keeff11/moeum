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

	/**
	 * 이미 환불된 누적. 세금 안분의 기준이다.
	 *
	 * <b>정산 후 직접 이체건({@code settled_manual})은 빼고 센다</b> (D-059). 그 돈은
	 * 셀러 계좌에서 나갔지 point3 세션에서 나간 것이 아니다 — 여기 섞으면 point3 쪽
	 * 취소 가능 잔액을 실제보다 적게 보고 남은 금액을 못 돌려주게 된다.
	 */
	@Query("""
			select new store.moeum.moeum.payment.refund.RefundedTotals(
			         coalesce(sum(r.amount), 0L),
			         coalesce(sum(r.vat), 0L),
			         coalesce(sum(r.taxFreeAmount), 0L))
			  from Refund r
			 where r.paymentId = :paymentId
			   and r.status = store.moeum.moeum.payment.refund.RefundStatus.COMPLETED
			   and r.settledManual = false
			""")
	RefundedTotals sumCompleted(@Param("paymentId") Long paymentId);

	/**
	 * 셀러 결제 내역(G10)이 쓴다. 줄마다 취소 상태를 찍어야 하는데 한 결제씩 부르면
	 * 목록 크기만큼 조회가 나간다 — 한 번에 끌어와 결제 id 로 접는다.
	 */
	List<Refund> findByPaymentIdIn(List<Long> paymentIds);

	/**
	 * 이 묶음에서 확정된 취소의 요청 주체. 최근 것이 앞에 온다.
	 *
	 * <b>payment 를 거쳐 묶음까지 올라간다.</b> 1차금과 2차금이 서로 다른 payment 라
	 * 한 차수만 보면 다른 차수에서 취소한 주체를 놓친다 — 묶음을 취소하면 차수마다
	 * refund 행이 하나씩 생긴다.
	 *
	 * <b>COMPLETED 만 본다.</b> PROCESSING 은 아직 환불이 확정되지 않은 것이라,
	 * 그걸로 "판매자가 취소했습니다" 를 띄우면 거절된 취소가 취소된 것으로 보인다.
	 */
	@Query("""
			select r.requestedBy
			  from Refund r, store.moeum.moeum.payment.domain.Payment p
			 where p.id = r.paymentId
			   and p.orderGroup.id = :orderGroupId
			   and r.status = store.moeum.moeum.payment.refund.RefundStatus.COMPLETED
			 order by r.id desc
			""")
	List<RefundRequester> findCompletedRequesters(@Param("orderGroupId") Long orderGroupId);

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
