package store.moeum.moeum.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByOrderGroupIdAndPhase(Long orderGroupId, PaymentPhase phase);

	Optional<Payment> findBySessionId(String sessionId);

	/**
	 * 셀러 주문 목록이 쓴다. 카드마다 "1차금 완료 · 2차금 미납" 을 찍어야 하는데
	 * 묶음당 최대 2행(차수)이라, 한 건씩 부르면 목록 크기의 두 배만큼 조회가 나간다.
	 */
	List<Payment> findByOrderGroupIdIn(List<Long> orderGroupIds);

	/**
	 * 진행 중인 결제 (D-042).
	 *
	 * <b>구매자가 orderToken 을 잃어버렸을 때 되찾는 유일한 경로다.</b> 토큰은 {@code /pay}
	 * 응답으로만 내려가므로, 새로고침하거나 브라우저를 닫으면 결제 중인 주문을 가리킬 방법이
	 * 사라진다 — 폴링도 confirm 도 못 한다.
	 *
	 * <b>끝난 묶음은 뺀다.</b> 만료 · 취소 · 실패한 건을 "진행 중" 으로 주면 이어서 결제하라는
	 * 화면이 뜨는데 그 세션은 이미 죽어 있다.
	 *
	 * 차수를 가리지 않는다 — 1차금이든 2차금이든 구매자 입장에서는 똑같이 "내다 만 결제" 다.
	 * 최근 것이 위에 온다.
	 */
	@Query("""
			select p from Payment p
			  join fetch p.orderGroup g
			 where g.buyer.kakaoId = :kakaoId
			   and p.status in (store.moeum.moeum.payment.domain.PaymentStatus.CREATED,
			                    store.moeum.moeum.payment.domain.PaymentStatus.CAPTURE_PENDING)
			   and g.status not in (store.moeum.moeum.order.domain.OrderGroupStatus.EXPIRED,
			                        store.moeum.moeum.order.domain.OrderGroupStatus.CANCELED,
			                        store.moeum.moeum.order.domain.OrderGroupStatus.FAILED)
			 order by p.updatedAt desc, p.id desc
			""")
	List<Payment> findInProgressByBuyer(@Param("kakaoId") String kakaoId);

	/**
	 * 확정 처리용 잠금 조회.
	 *
	 * 실시간 승인과 대사 배치가 같은 결제를 동시에 확정하려 할 수 있다.
	 * 잠그지 않으면 둘 다 "아직 CAPTURED 가 아니다" 를 읽고 각자 재고를 차감한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Payment p where p.id = :id")
	Optional<Payment> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 대사 배치가 집어갈 미확정 건 (D-005).
	 *
	 * <b>이 배치가 가장 중요한 배치다.</b> 승인 마감이 커밋 다음 날 00:00 KST 라
	 * 그 전에 확정하지 못하면 복구할 수 없다.
	 *
	 * SKIP LOCKED 로 인스턴스 간 분산하고, 방금 만들어진 건은 제외한다 —
	 * 실시간 처리가 아직 진행 중일 수 있어 곧바로 끼어들면 승인을 두 번 부른다.
	 */
	@Query(value = """
			SELECT * FROM payment
			 WHERE status = 'CAPTURE_PENDING'
			   AND updated_at <= :threshold
			 ORDER BY updated_at
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Payment> findPendingForUpdate(@Param("threshold") LocalDateTime threshold,
	                                   @Param("limit") int limit);
}
