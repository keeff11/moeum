package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import store.moeum.moeum.order.dto.PurchaseOrderLine;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

	/**
	 * 입고 처리 대상. 1차금이 확정된 주문만 집는다.
	 *
	 * 돈을 안 받은 주문을 입고 처리하면 2차금 청구 대상에 섞여 잔금만 청구하게 된다.
	 * 묶음까지 fetch 하는 이유는 입고 후 2차금 청구 가능 여부를 바로 판단하기 위해서다.
	 *
	 * <b>PAID 하나만 보면 안 된다</b> (D-049). 공동구매는 결제 직후 RECRUITING 으로 가고
	 * 마감 · 발주를 거치는데, {@code = PAID} 로 두면 그 단계를 밟은 주문이 여기서
	 * 한 건도 안 잡혀 <b>입고 처리가 0건이 되고 2차금이 영영 안 열린다.</b>
	 * 진행 단계가 아니라 "1차금이 확정됐고 아직 입고 전" 이 기준이다.
	 */
	@Query("""
			select o from Order o
			  join fetch o.orderGroup
			 where o.saleForm.id = :saleFormId
			   and o.status in (
			         store.moeum.moeum.order.domain.OrderStatus.PAID,
			         store.moeum.moeum.order.domain.OrderStatus.RECRUITING,
			         store.moeum.moeum.order.domain.OrderStatus.CLOSED,
			         store.moeum.moeum.order.domain.OrderStatus.PRODUCING)
			""")
	List<Order> findArrivableBySaleForm(@Param("saleFormId") Long saleFormId);

	/**
	 * 발주 처리 대상. 모집이 마감된 주문만이다 (D-049).
	 *
	 * 모집이 끝나야 몇 개를 만들지가 정해진다. 그 전에 발주하면 발주서(D-045)의
	 * 수량과 어긋난다.
	 */
	@Query("""
			select o from Order o
			 where o.saleForm.id = :saleFormId
			   and o.status = store.moeum.moeum.order.domain.OrderStatus.CLOSED
			""")
	List<Order> findClosedBySaleForm(@Param("saleFormId") Long saleFormId);

	/**
	 * 모집 마감 대상. 판매 폼이 마감될 때 그 폼의 주문을 같이 넘긴다 (D-049).
	 *
	 * 모집 중인 것만 집는다 — 이미 발주나 입고로 올라간 주문을 마감으로 되돌리면
	 * 진행이 거꾸로 간다.
	 */
	@Query("""
			select o from Order o
			 where o.saleForm.id = :saleFormId
			   and o.status = store.moeum.moeum.order.domain.OrderStatus.RECRUITING
			""")
	List<Order> findRecruitingBySaleForm(@Param("saleFormId") Long saleFormId);

	/**
	 * 목표수량 미달로 취소해야 할 주문 (D-026).
	 *
	 * 결제 전(CREATED)은 넣지 않는다 — 돌려줄 돈이 없고 홀드는 만료 배치가 푼다.
	 * 이미 CANCELED · EXPIRED 인 것도 뺀다.
	 */
	@Query("""
			select o.id from Order o
			 where o.saleForm.id = :saleFormId
			   and o.status not in (
			         store.moeum.moeum.order.domain.OrderStatus.CREATED,
			         store.moeum.moeum.order.domain.OrderStatus.CANCELED,
			         store.moeum.moeum.order.domain.OrderStatus.EXPIRED)
			 order by o.id
			""")
	List<Long> findCancelableIdsBySaleForm(@Param("saleFormId") Long saleFormId);

	/**
	 * 발주서 — 폼별 · 옵션별 수량 집계.
	 *
	 * <b>CREATED 를 뺀다.</b> domain.md 6절의 예시 쿼리는 {@code CANCELED · EXPIRED} 만
	 * 빼는데, 그러면 <b>결과를 모르는 결제가 발주서에 들어간다.</b> 주문은 승인이
	 * {@code captured} 로 확인된 뒤에야 PAID 가 되므로(PaymentWriter#finalizeCapture),
	 * CAPTURE_PENDING 인 건은 여전히 CREATED 다. 그걸 세면 돈이 안 들어온 수량까지
	 * 공장에 발주하게 된다.
	 *
	 * <b>PAID 로 좁히지도 않는다.</b> 상태가 PAID → RECRUITING → CLOSED → PRODUCING →
	 * ARRIVED 로 흐르기 때문에, PAID 만 세면 발주를 넣고 PRODUCING 으로 올린 뒤에
	 * 다시 받는 발주서가 빈 파일이 된다.
	 *
	 * <b>CANCELED 는 넣고 따로 센다.</b> 빼 버리면 셀러가 "원래 몇 개였고 몇 개가
	 * 취소됐는지" 를 대조할 수 없다. 실제 발주 수량은 둘의 차다.
	 *
	 * 이름은 스냅샷이 아니라 product · product_option 의 현재 이름을 쓴다
	 * ({@link PurchaseOrderLine} 참고). 정렬도 셀러가 폼에서 정한 순서 그대로다.
	 */
	@Query("""
			select new store.moeum.moeum.order.dto.PurchaseOrderLine(
			         p.name,
			         po.name,
			         sum(i.qty),
			         sum(case when o.status = store.moeum.moeum.order.domain.OrderStatus.CANCELED
			                  then i.qty else 0L end))
			  from OrderItem i
			  join i.order o
			  join i.product p
			  join i.option po
			 where o.saleForm.id = :saleFormId
			   and o.status not in (
			         store.moeum.moeum.order.domain.OrderStatus.CREATED,
			         store.moeum.moeum.order.domain.OrderStatus.EXPIRED)
			 group by p.id, p.name, p.sortOrder, po.id, po.name, po.sortOrder
			 order by p.sortOrder, po.sortOrder
			""")
	List<PurchaseOrderLine> findPurchaseOrderLines(@Param("saleFormId") Long saleFormId);

	/**
	 * 마감된 폼의 모집 중 주문을 한꺼번에 마감으로 넘긴다 (D-049).
	 *
	 * 마감 배치가 부른다. 엔티티를 올리지 않는 이유는 폼 마감과 같다 — 수백 폼이
	 * 한꺼번에 마감돼도 부담이 없다.
	 *
	 * <b>RECRUITING 만 건드린다.</b> 이미 발주나 입고로 올라간 주문을 마감으로 되돌리면
	 * 진행이 거꾸로 간다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE orders o
			  JOIN sale_form f ON f.id = o.sale_form_id
			   SET o.status = 'CLOSED'
			 WHERE o.status = 'RECRUITING'
			   AND f.status IN ('CLOSED', 'ENDED')
			""", nativeQuery = true)
	int closeRecruitingOfClosedForms();
}
