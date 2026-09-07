package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/**
	 * 입고 처리 대상. 1차금이 확정된 주문만 집는다.
	 *
	 * 돈을 안 받은 주문을 입고 처리하면 2차금 청구 대상에 섞여 잔금만 청구하게 된다.
	 * 묶음까지 fetch 하는 이유는 입고 후 2차금 청구 가능 여부를 바로 판단하기 위해서다.
	 */
	@Query("""
			select o from Order o
			  join fetch o.orderGroup
			 where o.saleForm.id = :saleFormId
			   and o.status = store.moeum.moeum.order.domain.OrderStatus.PAID
			""")
	List<Order> findPaidBySaleForm(@Param("saleFormId") Long saleFormId);
}
