package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrderGroupRepository extends JpaRepository<OrderGroup, Long> {

	Optional<OrderGroup> findBySessionToken(String sessionToken);

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
