package store.moeum.moeum.payment.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
	 * 구매자 구매 목록(B13)이 쓴다 — 묶음별로 <b>실제로 출금된 금액</b> 합계다.
	 *
	 * <b>{@code CAPTURED} 만 센다.</b> {@code CAPTURE_PENDING} 은 승인 결과를 모르는
	 * 상태이지 돈이 들어온 상태가 아니다 (D-006). 그걸 "낸 금액" 에 올리면 실제로는
	 * 실패한 결제를 구매자가 낸 것으로 보게 되고, 2차금 청구를 받고도 이미 낸 줄 안다.
	 * {@code PaymentSummary.isSettled} 와 같은 기준이다.
	 *
	 * 환불은 여기서 빼지 않는다 — {@code payment.refunded_amount} 는 0 으로 만들어진 뒤
	 * 아무도 갱신하지 않는 컬럼이라, 실제 기준인 refund 행 합계를 부르는 쪽이 따로 뺀다.
	 */
	@Query("""
			select new store.moeum.moeum.payment.domain.GroupAmount(
			         p.orderGroup.id, coalesce(sum(p.amount), 0L))
			  from Payment p
			 where p.orderGroup.id in :orderGroupIds
			   and p.status = store.moeum.moeum.payment.domain.PaymentStatus.CAPTURED
			 group by p.orderGroup.id
			""")
	List<GroupAmount> sumCapturedByOrderGroupIdIn(@Param("orderGroupIds") List<Long> orderGroupIds);

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
	 * 셀러 결제 내역 (와이어프레임 G10 · D-059). 결제한 시각 기준 최신순이다.
	 *
	 * <b>줄 하나가 결제 한 건</b>이다 — 묶음 × 차수. 1차금과 2차금은 세션도 금액도
	 * 취소 경로도 달라서 묶음으로 접으면 화면의 '구분 · 1차금' 칸을 채울 수 없다.
	 *
	 * 네이티브로 쓴 이유는 <b>상태 판정을 목록·건수·칩 숫자 셋이 나눠 쓰기</b> 위해서다.
	 * 판정문은 {@link SellerPaymentSql#STATUS_CASE} 하나뿐이고 세 쿼리가 그것을 이어 붙인다.
	 *
	 * @param status {@code SellerPaymentStatus} 의 이름. null 이면 '전체' 다
	 */
	@Query(value = SellerPaymentSql.LIST, countQuery = SellerPaymentSql.LIST_COUNT,
			nativeQuery = true)
	Page<Payment> findSellerPayments(@Param("sellerId") Long sellerId,
	                                 @Param("status") String status,
	                                 @Param("saleFormId") Long saleFormId,
	                                 @Param("keyword") String keyword,
	                                 Pageable pageable);

	/**
	 * 결제번호로 한 건 찾기 (G10 상세 · S14 · D-059).
	 *
	 * <b>묶음과 셀러까지 같이 끌어온다.</b> 소유권 확인과 응답 조립에 어차피 필요한 값이라
	 * 지연 로딩으로 두면 조회가 세 번 나가고, 트랜잭션 밖에서 부르면 아예 터진다.
	 */
	@Query("""
			select p from Payment p
			  join fetch p.orderGroup g
			  join fetch g.seller
			 where g.orderNo = :orderNo
			   and p.phase = :phase
			""")
	Optional<Payment> findByOrderNoAndPhase(@Param("orderNo") String orderNo,
	                                        @Param("phase") PaymentPhase phase);

	/**
	 * 칩 숫자. {@code (상태, 건수)} 줄로 돌아온다 — {@code SellerPaymentCounts.of} 가 편다.
	 *
	 * <b>칩 조건만 빼고 목록과 같은 필터를 건다.</b> 갈라지면 칩에는 3건이라 적혀 있는데
	 * 눌러서 열면 2건인 화면이 나온다.
	 */
	@Query(value = SellerPaymentSql.TALLY, nativeQuery = true)
	List<Object[]> tallySellerPayments(@Param("sellerId") Long sellerId,
	                                   @Param("saleFormId") Long saleFormId,
	                                   @Param("keyword") String keyword);

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
