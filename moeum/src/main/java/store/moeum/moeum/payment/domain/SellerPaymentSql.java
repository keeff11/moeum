package store.moeum.moeum.payment.domain;

/**
 * 셀러 결제 내역(와이어프레임 G10)의 SQL 조각. <b>상태 판정을 한 군데에만 적는다</b> (D-059).
 *
 * 목록 · 건수 · 칩 숫자 셋이 같은 판정을 써야 한다. 셋에 따로 적으면 칩에는 3건이라
 * 적혀 있는데 눌러서 열면 2건인 화면이 나온다 — G6 에서 탭 배지와 목록이 갈라지면
 * 돈 문제가 된다고 본 것과 같은 이유다 (D-038 결정 2).
 *
 * <b>그래서 자바 상수로 빼서 이어 붙인다.</b> {@code @Query} 의 값은 컴파일 타임 상수여야
 * 하는데, {@code final String} 끼리의 {@code +} 는 상수식이라 그대로 쓸 수 있다.
 * 뷰나 함수로 빼지 않은 것은 Flyway 로 관리할 스키마 물건을 하나 더 만들고 싶지 않아서다.
 *
 * <b>줄 하나가 결제 한 건이다.</b> 주문묶음 × 차수 — 1차금과 2차금은 서로 다른 세션이고
 * 따로 취소되며 금액도 다르다. 묶음으로 접으면 화면의 '구분 · 1차금' 칸을 채울 수 없다.
 * 셀러 주문 목록(G6)이 묶음 단위인 것과 일부러 다르게 둔 것이다 (D-033).
 */
public final class SellerPaymentSql {

	private SellerPaymentSql() {
	}

	/**
	 * 칩 하나를 정하는 판정. <b>위에서부터 먼저 맞는 것을 쓴다.</b>
	 *
	 * 순서가 곧 정책이다.
	 * <ol>
	 *   <li><b>취소 처리중이 가장 위다.</b> 결과를 모르는 건이라 셀러가 또 손대면 안 된다</li>
	 *   <li><b>그다음이 정산 완료다.</b> 시스템으로 못 되돌려 셀러가 직접 이체해야 하는
	 *       건이고, 여기 걸린 숫자가 곧 S14 의 할 일 목록이다</li>
	 *   <li>취소 완료 — 직접 이체를 마친 건도 여기로 내려온다</li>
	 *   <li>처리 실패 — 결제가 실패했거나 취소가 확정 거절됐다</li>
	 *   <li>결제 완료</li>
	 * </ol>
	 *
	 * <b>{@code CAPTURE_PENDING} 은 실패가 아니다</b> (CLAUDE.md 규칙 3). 결과를 모르는
	 * 것뿐이라 {@code PENDING} 으로 따로 빼 둔다 — 와이어프레임에 칩이 없어 '전체' 에만 보인다.
	 */
	public static final String STATUS_CASE = """
			CASE
			  WHEN EXISTS (SELECT 1 FROM refund r
			                WHERE r.payment_id = p.id AND r.status = 'PROCESSING')
			       THEN 'CANCELING'
			  WHEN EXISTS (SELECT 1 FROM refund r
			                WHERE r.payment_id = p.id
			                  AND r.settled_manual = 1 AND r.manual_refunded_at IS NULL)
			       THEN 'SETTLED'
			  WHEN EXISTS (SELECT 1 FROM refund r
			                WHERE r.payment_id = p.id AND r.status = 'COMPLETED')
			       THEN 'CANCELED'
			  WHEN p.status = 'FAILED'
			    OR EXISTS (SELECT 1 FROM refund r
			                WHERE r.payment_id = p.id
			                  AND r.status = 'FAILED' AND r.settled_manual = 0)
			       THEN 'FAILED'
			  WHEN p.status = 'CAPTURED' THEN 'PAID'
			  ELSE 'PENDING'
			END""";

	/**
	 * 노출 범위와 선택 필터. 칩 조건만 빠져 있다.
	 *
	 * <b>{@code CREATED} 결제와 만료된 묶음은 내역이 아니다.</b> 결제창에 들어가지도 않은
	 * 세션이라 30분 뒤 사라진다 — 셀러 주문 목록이 같은 것을 빼는 이유와 같다 (D-033).
	 *
	 * 검색어는 G6 와 같은 네 곳을 본다 — 주문번호 · 수령인 · 판매 제목 · 상품명.
	 * 화면의 안내는 '결제 번호 검색' 과 '상품 이름 검색' 두 가지인데, 어느 쪽을 쳐도
	 * 걸리게 두는 편이 낫다. {@code %} 와 {@code _} 는 호출하는 쪽에서 escape 한다.
	 */
	private static final String FILTER = """
			  FROM payment p
			  JOIN order_group g ON g.id = p.order_group_id
			 WHERE g.seller_id = :sellerId
			   AND p.status <> 'CREATED'
			   AND g.status <> 'EXPIRED'
			   AND (:saleFormId IS NULL
			        OR EXISTS (SELECT 1 FROM orders o
			                    WHERE o.order_group_id = g.id AND o.sale_form_id = :saleFormId))
			   AND (:keyword IS NULL
			        OR g.order_no LIKE :keyword ESCAPE '!'
			        OR EXISTS (SELECT 1 FROM shipping s
			                    WHERE s.order_group_id = g.id
			                      AND s.recipient_name LIKE :keyword ESCAPE '!')
			        OR EXISTS (SELECT 1 FROM orders o
			                    JOIN sale_form f ON f.id = o.sale_form_id
			                   WHERE o.order_group_id = g.id
			                     AND f.title LIKE :keyword ESCAPE '!')
			        OR EXISTS (SELECT 1 FROM orders o
			                    JOIN order_item i ON i.order_id = o.id
			                   WHERE o.order_group_id = g.id
			                     AND i.product_name LIKE :keyword ESCAPE '!'))
			""";

	/** 칩 필터. {@code :status} 가 null 이면 '전체' 다 */
	private static final String STATUS_FILTER =
			"   AND (:status IS NULL OR " + STATUS_CASE + " = :status)\n";

	/**
	 * 목록. <b>결제한 시각 기준 최신순</b>이다.
	 *
	 * {@code created_at} 이 아니라 {@code captured_at} 을 먼저 보는 이유는, 셀러가 찾는
	 * 것이 "언제 돈이 들어왔는가" 이기 때문이다. 세션은 며칠 전에 만들어졌을 수 있다.
	 */
	public static final String LIST = "SELECT p.*\n" + FILTER + STATUS_FILTER
			+ " ORDER BY COALESCE(p.captured_at, p.created_at) DESC, p.id DESC";

	public static final String LIST_COUNT = "SELECT COUNT(*)\n" + FILTER + STATUS_FILTER;

	/**
	 * 칩 숫자를 한 번에 집계한다. 칩마다 COUNT 를 날릴 이유가 없다.
	 *
	 * <b>칩 조건만 빼고 검색어·판매별 필터는 그대로 건다.</b> 검색 중이면 칩도 그
	 * 검색 결과의 숫자여야 화면이 맞는다.
	 */
	public static final String TALLY =
			"SELECT " + STATUS_CASE + " AS pay_status, COUNT(*) AS cnt\n" + FILTER
					+ " GROUP BY pay_status";
}
