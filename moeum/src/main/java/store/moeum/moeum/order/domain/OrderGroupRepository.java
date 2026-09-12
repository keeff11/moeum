package store.moeum.moeum.order.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

	/**
	 * 아직 결제창에 들어가지 않은 세션 (D-015 · D-037).
	 *
	 * <b>CREATED 만 본다.</b> PAY_PENDING 부터는 결제창까지 간 것이라 이어받을 대상이 아니다 —
	 * 거기에 손대면 결제 중인 주문을 건드리게 된다.
	 *
	 * 셀러로 좁히지 않는 이유는 부르는 쪽이 아직 셀러를 모르기 때문이다. 셀러는 옵션에서
	 * 역산되는데(OrderCreator), 그러려면 이어받기 판정 전에 옵션을 한 번 더 읽어야 한다.
	 * 어차피 항목이 똑같은지 대조하므로 같은 항목이면 셀러도 같다.
	 */
	@Query("""
			select g from OrderGroup g
			 where g.buyer.id = :buyerId
			   and g.status = store.moeum.moeum.order.domain.OrderGroupStatus.CREATED
			 order by g.id desc
			""")
	List<OrderGroup> findActiveByBuyer(@Param("buyerId") Long buyerId);

	Optional<OrderGroup> findByOrderNo(String orderNo);

	/**
	 * 구매자 주문 목록 (와이어프레임 B13).
	 *
	 * <b>{@code CREATED} 와 {@code EXPIRED} 는 뺀다.</b> 결제 전 장바구니 세션은 주문이 아니다 —
	 * 15분 뒤 사라질 것이 "내 구매 목록" 에 쌓이면 안 된다. 셀러 목록과 같은 판단이다 (D-033).
	 *
	 * <b>취소된 주문은 남긴다.</b> 셀러 목록과 같다 — 구매자도 자기가 취소한 내역을 봐야 한다.
	 *
	 * 탭은 판매 유형이다(전체 · 공동구매 · 단독판매). 묶음 하나에 유형이 섞일 수는 없지만
	 * 폼을 타고 들어가야 알 수 있어서 EXISTS 로 건다.
	 *
	 * fetch join 을 걸지 않는다 — 컬렉션을 조인하면 LIMIT 이 행 기준으로 잘려
	 * 페이지 크기가 틀어진다. default_batch_fetch_size 가 끌어온다.
	 */
	@Query("""
			select g from OrderGroup g
			 where g.buyer.kakaoId = :kakaoId
			   and g.status not in (store.moeum.moeum.order.domain.OrderGroupStatus.CREATED,
			                        store.moeum.moeum.order.domain.OrderGroupStatus.EXPIRED)
			   and (:saleType is null
			        or exists (select 1 from Order o
			                    where o.orderGroup = g and o.saleForm.saleType = :saleType))
			 order by g.createdAt desc, g.id desc
			""")
	Page<OrderGroup> findBuyerOrders(@Param("kakaoId") String kakaoId,
	                                 @Param("saleType") store.moeum.moeum.saleform.domain.SaleType saleType,
	                                 Pageable pageable);

	/**
	 * 셀러 주문 목록 (와이어프레임 G6). 최신순이다.
	 *
	 * <b>fetch join 을 걸지 않는다.</b> orders · items 는 컬렉션이라 조인하면 LIMIT 이
	 * 행 기준으로 잘려 페이지 크기가 틀어진다. default_batch_fetch_size 가 끌어온다.
	 *
	 * {@code CREATED} 와 {@code EXPIRED} 는 뺀다 — 결제 전 체크아웃 세션은 주문이 아니다.
	 * 셀러에게 보여 주면 15분 뒤 사라질 것이 목록에 쌓인다.
	 *
	 * 네이티브로 쓰지 않은 이유는 넘기는 파라미터에 enum 이 없기 때문이다.
	 * {@code findStorePage} 가 네이티브인 것은 enum 파라미터의 null 비교 때문이었고,
	 * 여기 탭은 문자열이라 그 문제가 없다. EXISTS 가 여럿이라 JPQL 쪽이 읽기도 낫다.
	 *
	 * <b>PREPARING 은 2차금이 없는 묶음도 받는다</b> (D-046). 단독 판매는 묶음 상태가
	 * SECOND_PAID 로 넘어가지 않고 PAID 에 머물러서, 그 상태만 보면 발송할 주문이
	 * 어느 탭에도 나오지 않는다. 잔금이 없으면 입고가 곧 발송 준비 완료다.
	 *
	 * <b>SECOND_UNPAID 는 2차금이 있는 묶음만이다.</b> {@code deposit2Total = 0} 이면
	 * 배송비까지 1차금에서 받았으므로 청구할 것이 없다 (D-046) — 단독 판매가 그렇다.
	 * {@code OrderGroup.hasSecondPayment()} 와 같은 조건이고, 갈라지면 탭 숫자와
	 * 일괄 청구(S10) 대상이 어긋난다.
	 *
	 * <b>SECOND_UNPAID 는 살아 있는 주문이 하나라도 있어야 한다.</b> 취소를 제외하고
	 * "입고 안 된 것이 없다" 만 보면 <b>전부 취소된 묶음도 통과한다</b> — 받을 잔금이 없는데
	 * 청구 대상에 섞인다. {@code OrderGroup.isSecondPaymentDue()} 의 {@code !alive.isEmpty()}
	 * 와 같은 조건이다 (D-035).
	 *
	 * @param tab {@code SellerOrderTab} 의 이름. null 이나 ALL 이면 상태로 거르지 않는다
	 */
	@Query("""
			select g from OrderGroup g
			 where g.seller.id = :sellerId
			   and g.status not in (store.moeum.moeum.order.domain.OrderGroupStatus.CREATED,
			                        store.moeum.moeum.order.domain.OrderGroupStatus.EXPIRED)
			   and (:tab is null or :tab = 'ALL'
			        or (:tab = 'PAYMENT_WAITING'
			            and g.status = store.moeum.moeum.order.domain.OrderGroupStatus.PAY_PENDING)
			        or (:tab = 'SECOND_UNPAID'
			            and g.status in (store.moeum.moeum.order.domain.OrderGroupStatus.PAID,
			                             store.moeum.moeum.order.domain.OrderGroupStatus.SECOND_PENDING)
			            and g.deposit2Total > 0
			            and exists (select 1 from Order a
			                         where a.orderGroup = g
			                           and a.status = store.moeum.moeum.order.domain.OrderStatus.ARRIVED)
			            and not exists (select 1 from Order n
			                             where n.orderGroup = g
			                               and n.status not in (store.moeum.moeum.order.domain.OrderStatus.CANCELED,
			                                                    store.moeum.moeum.order.domain.OrderStatus.EXPIRED,
			                                                    store.moeum.moeum.order.domain.OrderStatus.ARRIVED)))
			        or (:tab = 'PREPARING'
			            and (g.status = store.moeum.moeum.order.domain.OrderGroupStatus.SECOND_PAID
			                 or (g.status = store.moeum.moeum.order.domain.OrderGroupStatus.PAID
			                     and g.deposit2Total = 0
			                     and exists (select 1 from Order p
			                                  where p.orderGroup = g
			                                    and p.status = store.moeum.moeum.order.domain.OrderStatus.ARRIVED)
			                     and not exists (select 1 from Order q
			                                      where q.orderGroup = g
			                                        and q.status not in (store.moeum.moeum.order.domain.OrderStatus.CANCELED,
			                                                             store.moeum.moeum.order.domain.OrderStatus.EXPIRED,
			                                                             store.moeum.moeum.order.domain.OrderStatus.ARRIVED)))))
			        or (:tab = 'SHIPPED'
			            and g.status = store.moeum.moeum.order.domain.OrderGroupStatus.SHIPPED))
			   and (:saleFormId is null
			        or exists (select 1 from Order f
			                    where f.orderGroup = g and f.saleForm.id = :saleFormId))
			   and (:keyword is null
			        or g.orderNo like :keyword escape '!'
			        or exists (select 1 from Shipping s
			                    where s.orderGroup = g and s.recipientName like :keyword escape '!')
			        or exists (select 1 from Order t
			                    where t.orderGroup = g and t.saleForm.title like :keyword escape '!')
			        or exists (select 1 from OrderItem i
			                    where i.order.orderGroup = g and i.productName like :keyword escape '!'))
			 order by g.createdAt desc, g.id desc
			""")
	Page<OrderGroup> findSellerOrders(@Param("sellerId") Long sellerId,
	                                  @Param("tab") String tab,
	                                  @Param("saleFormId") Long saleFormId,
	                                  @Param("keyword") String keyword,
	                                  Pageable pageable);

	/**
	 * 탭 배지 숫자를 한 번에 집계한다.
	 *
	 * 목록 쿼리에서 탭 조건만 빼고 나머지 필터는 그대로 건다 — 검색 중이면 배지도
	 * 그 검색 결과의 숫자여야 화면이 맞는다.
	 *
	 * <b>2차금 미납은 {@code deposit2Total > 0} 인 묶음만 센다</b> (D-046).
	 * 목록 쿼리({@code findSellerOrders})의 SECOND_UNPAID 조건과 같아야 한다 —
	 * 갈라지면 배지 숫자와 목록 건수가 어긋나고, 셀러는 열리지 않는 탭에 숫자만 보게 된다.
	 *
	 * <b>발송 완료는 당분간 항상 0 이다.</b> SHIPPED 로 올리는 코드가 아직 없다
	 * (송장 등록은 7단계). 탭을 지우지 않는 이유는 화면이 다섯 칸이기 때문이다.
	 */
	@Query("""
			select new store.moeum.moeum.order.domain.SellerOrderCounts(
			       count(g),
			       count(case when g.status = store.moeum.moeum.order.domain.OrderGroupStatus.PAY_PENDING
			                  then 1 end),
			       count(case when g.status in (store.moeum.moeum.order.domain.OrderGroupStatus.PAID,
			                                    store.moeum.moeum.order.domain.OrderGroupStatus.SECOND_PENDING)
			                   and g.deposit2Total > 0
			                   and exists (select 1 from Order a
			                                where a.orderGroup = g
			                                  and a.status = store.moeum.moeum.order.domain.OrderStatus.ARRIVED)
			                   and not exists (select 1 from Order n
			                                    where n.orderGroup = g
			                                      and n.status not in (store.moeum.moeum.order.domain.OrderStatus.CANCELED,
			                                                           store.moeum.moeum.order.domain.OrderStatus.EXPIRED,
			                                                           store.moeum.moeum.order.domain.OrderStatus.ARRIVED))
			                  then 1 end),
			       count(case when g.status = store.moeum.moeum.order.domain.OrderGroupStatus.SECOND_PAID
			                   or (g.status = store.moeum.moeum.order.domain.OrderGroupStatus.PAID
			                       and g.deposit2Total = 0
			                       and exists (select 1 from Order p
			                                    where p.orderGroup = g
			                                      and p.status = store.moeum.moeum.order.domain.OrderStatus.ARRIVED)
			                       and not exists (select 1 from Order q
			                                        where q.orderGroup = g
			                                          and q.status not in (store.moeum.moeum.order.domain.OrderStatus.CANCELED,
			                                                               store.moeum.moeum.order.domain.OrderStatus.EXPIRED,
			                                                               store.moeum.moeum.order.domain.OrderStatus.ARRIVED)))
			                  then 1 end),
			       count(case when g.status = store.moeum.moeum.order.domain.OrderGroupStatus.SHIPPED
			                  then 1 end))
			  from OrderGroup g
			 where g.seller.id = :sellerId
			   and g.status not in (store.moeum.moeum.order.domain.OrderGroupStatus.CREATED,
			                        store.moeum.moeum.order.domain.OrderGroupStatus.EXPIRED)
			   and (:saleFormId is null
			        or exists (select 1 from Order f
			                    where f.orderGroup = g and f.saleForm.id = :saleFormId))
			   and (:keyword is null
			        or g.orderNo like :keyword escape '!'
			        or exists (select 1 from Shipping s
			                    where s.orderGroup = g and s.recipientName like :keyword escape '!')
			        or exists (select 1 from Order t
			                    where t.orderGroup = g and t.saleForm.title like :keyword escape '!')
			        or exists (select 1 from OrderItem i
			                    where i.order.orderGroup = g and i.productName like :keyword escape '!'))
			""")
	SellerOrderCounts countSellerOrderTabs(@Param("sellerId") Long sellerId,
	                                       @Param("saleFormId") Long saleFormId,
	                                       @Param("keyword") String keyword);
}
