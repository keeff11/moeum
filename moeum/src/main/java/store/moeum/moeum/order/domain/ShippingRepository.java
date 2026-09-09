package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShippingRepository extends JpaRepository<Shipping, Long> {

	Optional<Shipping> findByOrderGroupId(Long orderGroupId);

	/**
	 * 셀러 주문 목록이 쓴다. <b>한 건씩 부르면 목록 크기만큼 조회가 나간다.</b>
	 * OrderGroup 쪽에 연관을 두지 않아서(스냅샷이라 묶음이 배송지를 소유할 이유가 없다)
	 * batch fetch 가 걸리지 않는다 — 여기서 한 번에 끌어와 애플리케이션에서 붙인다.
	 */
	List<Shipping> findByOrderGroupIdIn(List<Long> orderGroupIds);
}
