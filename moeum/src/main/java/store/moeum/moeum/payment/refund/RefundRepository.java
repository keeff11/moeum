package store.moeum.moeum.payment.refund;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import store.moeum.moeum.payment.domain.GroupAmount;

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
	 * 구매자 구매 목록(B13)이 쓴다 — 묶음별로 <b>구매자에게 실제로 돌아간 금액</b> 합계다.
	 *
	 * <b>{@link #sumCompleted} 와 기준이 다르다.</b> 저쪽은 point3 세션에서 얼마나
	 * 빠져나갔는가(세금 안분의 기준)를 묻고, 여기는 구매자 지갑에 얼마가 돌아왔는가를 묻는다.
	 * 그래서 정산 후 직접 이체건({@code settled_manual})을 빼지 않고, 대신
	 * <b>셀러가 실제로 이체를 마쳤는지({@code manual_refunded_at})</b> 를 본다 (D-059).
	 * 접수만 되고 이체가 안 끝난 건을 돌려준 것으로 세면, 구매자는 받지도 않은 돈을
	 * 환불받은 것으로 보게 된다.
	 *
	 * <b>{@code COMPLETED} 만 센다.</b> {@code PROCESSING} 은 아직 확정되지 않은 취소라
	 * 거절되면 되돌아온다 — 미리 빼면 낸 금액이 잠깐 줄었다 늘어난다.
	 */
	@Query("""
			select new store.moeum.moeum.payment.domain.GroupAmount(
			         p.orderGroup.id, coalesce(sum(r.amount), 0L))
			  from Refund r, store.moeum.moeum.payment.domain.Payment p
			 where p.id = r.paymentId
			   and p.orderGroup.id in :orderGroupIds
			   and r.status = store.moeum.moeum.payment.refund.RefundStatus.COMPLETED
			   and (r.settledManual = false or r.manualRefundedAt is not null)
			 group by p.orderGroup.id
			""")
	List<GroupAmount> sumRefundedByOrderGroupIdIn(@Param("orderGroupIds") List<Long> orderGroupIds);

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
