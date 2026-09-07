package store.moeum.moeum.order.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderGroupRepository extends JpaRepository<OrderGroup, Long> {

	Optional<OrderGroup> findBySessionToken(String sessionToken);

	Optional<OrderGroup> findByOrderToken(String orderToken);

	/**
	 * 확정 처리용 잠금 조회.
	 *
	 * 실시간 승인과 대사 배치가 같은 묶음을 동시에 확정하려 할 수 있다.
	 * 잠그지 않으면 둘 다 "아직 PAID 가 아니다" 를 읽고 각자 재고를 차감한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from OrderGroup g where g.id = :id")
	Optional<OrderGroup> findByIdForUpdate(@Param("id") Long id);

	/** 진행 중인 세션. 복귀 후 자동 재호출이 중복돼도 기존 홀드를 이어받게 한다 (D-015) */
	@Query("""
			select g from OrderGroup g
			 where g.buyer.id = :buyerId
			   and g.seller.id = :sellerId
			   and g.status = store.moeum.moeum.order.domain.OrderGroupStatus.CREATED
			 order by g.id desc
			""")
	List<OrderGroup> findActiveByBuyerAndSeller(Long buyerId, Long sellerId);
}
