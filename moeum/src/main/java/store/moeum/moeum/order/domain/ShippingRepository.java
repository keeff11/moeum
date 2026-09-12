package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import store.moeum.moeum.order.dto.ShipmentRef;

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

	/**
	 * 배송조회에 필요한 값만 한 쿼리로 꺼낸다 (D-048).
	 *
	 * <b>구매자 본인 것만 나온다.</b> 소유권을 쿼리에 박아서 지킨다 — 꺼낸 뒤에
	 * 거르는 방식은 한 군데만 빠뜨려도 남의 송장번호가 샌다.
	 *
	 * 엔티티가 아니라 값으로 받는 것은 트랜잭션 경계 때문이다 ({@link ShipmentRef} 참고).
	 */
	@Query("""
			select new store.moeum.moeum.order.dto.ShipmentRef(s.carrier, s.carrierCode, s.trackingNo)
			  from Shipping s
			 where s.orderGroup.orderToken = :orderToken
			   and s.orderGroup.buyer.kakaoId = :kakaoId
			""")
	Optional<ShipmentRef> findRefByOrderToken(@Param("orderToken") String orderToken,
	                                          @Param("kakaoId") String kakaoId);
}
