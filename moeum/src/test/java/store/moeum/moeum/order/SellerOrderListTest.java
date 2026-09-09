package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerAddress;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderGroupStatus;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.SellerOrderDetailResponse;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.order.dto.SellerOrderStatus;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 주문 목록 (와이어프레임 G6).
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li>남의 주문이 절대 섞이지 않는다</li>
 *   <li>결제 전 세션과 만료 건은 주문이 아니다 — 목록에도 탭 숫자에도 없다</li>
 *   <li>2차금 미납은 <b>지금 청구할 수 있는 것만</b>이다. 입고 전 주문이 섞이면 일괄 청구가 틀어진다</li>
 *   <li>구매자 연락처는 가려서 나가고, 배송지 본문은 아예 나가지 않는다</li>
 * </ul>
 */
class SellerOrderListTest extends IntegrationTest {

	private static final String SELLER_KAKAO = "kakao-g6-seller";
	private static final String OTHER_KAKAO = "kakao-g6-other";

	@Autowired
	private SellerOrderService sellerOrderService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private BuyerRepository buyerRepository;

	@Autowired
	private BuyerAddressRepository buyerAddressRepository;

	@Autowired
	private OrderGroupRepository orderGroupRepository;

	@Autowired
	private ShippingRepository shippingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	private Seller seller;
	private Seller otherSeller;
	private SaleForm standForm;
	private SaleForm badgeForm;
	private SaleForm otherForm;
	private Buyer buyer;
	private BuyerAddress address;

	private int tokenSeq;

	/** addOrder 가 담아 두고 persist 가 SQL 로 적용한다 */
	private final List<OrderStatus> pendingStatuses = new ArrayList<>();

	@BeforeEach
	void setUp() {
		for (String table : new String[]{
				"second_charge", "payment_event", "refund", "payment", "outbox",
				"stock_hold", "order_item", "orders", "shipping", "order_group",
				"cart_item", "cart", "wishlist", "buyer_address", "buyer",
				"sale_form_history", "sale_form_image", "product_option", "product", "sale_form", "seller"}) {
			jdbcTemplate.execute("DELETE FROM " + table);
		}
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
		tokenSeq = 0;

		seller = saveSeller(SELLER_KAKAO);
		otherSeller = saveSeller(OTHER_KAKAO);
		standForm = saveForm(seller, "아크릴 스탠드 — 2차 공구");
		badgeForm = saveForm(seller, "아크릴 뱃지 — 1차 공구");
		otherForm = saveForm(otherSeller, "남의 판매");

		buyer = buyerRepository.saveAndFlush(Buyer.of("kakao-g6-buyer", "구매자닉네임"));
		address = buyerAddressRepository.saveAndFlush(BuyerAddress.builder()
				.buyer(buyer)
				.recipientName("김서연")
				.phone("01012341234")
				.postalCode("06236")
				.address1("서울 강남구 테헤란로 1")
				.build());
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("결제_전_세션과_만료된_건은_주문이_아니다")
	void 결제_전_세션은_숨긴다() {
		place(standForm, OrderGroupStatus.CREATED, OrderStatus.CREATED);
		place(standForm, OrderGroupStatus.EXPIRED, OrderStatus.EXPIRED);
		String paid = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 15분 뒤 사라질 장바구니 세션이 셀러 목록에 쌓이면 안 된다
		assertThat(orderNos()).containsExactly(paid);
		assertThat(page().counts().all()).isEqualTo(1);
	}

	@Test
	@DisplayName("다른_셀러의_주문은_섞이지_않는다")
	void 셀러_격리() {
		String mine = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		placeFor(otherSeller, otherForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(orderNos()).containsExactly(mine);
	}

	@Test
	@DisplayName("최신_주문이_위에_온다")
	void 정렬() {
		String older = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		jdbcTemplate.update("UPDATE order_group SET created_at = ? WHERE order_no = ?",
				LocalDateTime.now().minusDays(3), older);
		String newer = place(badgeForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 처리할 주문이 위에 있어야 셀러가 스크롤하지 않는다
		assertThat(orderNos()).containsExactly(newer, older);
	}

	// ---------------------------------------------------------------- 상태 탭

	@Test
	@DisplayName("입고_전_주문은_2차금_미납이_아니라_진행_중이다")
	void 입고_전은_청구_대상이_아니다() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 아직 청구할 수 없는 건이 섞이면 일괄 청구(S10)의 대상 수가 틀어진다
		assertThat(page().counts().secondUnpaid()).isZero();
		assertThat(page(SellerOrderTab.SECOND_UNPAID).items()).isEmpty();
		assertThat(page().items().get(0).status()).isEqualTo(SellerOrderStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("전_폼이_입고되면_2차금_미납으로_넘어간다")
	void 입고_후_청구_대상() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		assertThat(page().counts().secondUnpaid()).isEqualTo(1);
		assertThat(orderNos(SellerOrderTab.SECOND_UNPAID)).containsExactly(orderNo);
		assertThat(page().items().get(0).status()).isEqualTo(SellerOrderStatus.SECOND_UNPAID);
	}

	@Test
	@DisplayName("한_폼이라도_입고_전이면_청구_대상이_아니다")
	void 일부만_입고() {
		OrderGroup group = newGroup();
		addOrder(group, standForm, OrderStatus.ARRIVED);
		addOrder(group, badgeForm, OrderStatus.PAID);
		persist(group, OrderGroupStatus.PAID);

		// 배송비가 묶음당 1회라 일부만 입고됐다고 청구하면 배송비를 나눌 방법이 없다
		assertThat(page().counts().secondUnpaid()).isZero();
	}

	@Test
	@DisplayName("탭_다섯_칸의_건수가_각각_맞는다")
	void 탭_건수() {
		place(standForm, OrderGroupStatus.PAY_PENDING, OrderStatus.CREATED);
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);
		place(standForm, OrderGroupStatus.SECOND_PENDING, OrderStatus.ARRIVED);
		place(standForm, OrderGroupStatus.SECOND_PAID, OrderStatus.ARRIVED);
		place(standForm, OrderGroupStatus.CANCELED, OrderStatus.CANCELED);

		SellerOrderPageResponse.TabCounts counts = page().counts();

		assertThat(counts.all()).isEqualTo(5);
		assertThat(counts.paymentWaiting()).isEqualTo(1);
		assertThat(counts.secondUnpaid()).isEqualTo(2);
		assertThat(counts.preparing()).isEqualTo(1);

		// 취소된 주문은 전체에만 남고 어느 처리 탭에도 잡히지 않는다
		assertThat(orderNos(SellerOrderTab.ALL)).hasSize(5);
	}

	@Test
	@DisplayName("발송_완료_탭은_아직_항상_0건이다")
	void 발송_완료는_비어_있다() {
		place(standForm, OrderGroupStatus.SECOND_PAID, OrderStatus.ARRIVED);

		// SHIPPED 로 올리는 코드가 없다 — 송장 등록이 7단계다. 0이 정상이다
		assertThat(page().counts().shipped()).isZero();
		assertThat(page(SellerOrderTab.SHIPPED).items()).isEmpty();
	}

	// ---------------------------------------------------------------- 검색 · 필터

	@Test
	@DisplayName("주문번호_수령인_상품명_판매제목으로_찾는다")
	void 검색() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		place(badgeForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(orderNos(orderNo.substring(orderNo.length() - 4))).contains(orderNo);
		assertThat(orderNos("김서")).hasSize(2);
		assertThat(orderNos("스탠드")).containsExactly(orderNo);
		assertThat(orderNos("뱃지")).hasSize(1);
		assertThat(orderNos("없는말")).isEmpty();
	}

	@Test
	@DisplayName("검색어의_와일드카드는_글자로_취급한다")
	void 와일드카드_이스케이프() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// escape 하지 않으면 % 하나로 주문 전체가 걸린다
		assertThat(orderNos("%")).isEmpty();
		assertThat(orderNos("_")).isEmpty();
	}

	@Test
	@DisplayName("검색_결과에_맞춰_탭_숫자도_줄어든다")
	void 검색과_탭_숫자() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);
		place(badgeForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		// 배지 숫자가 검색을 무시하면 목록은 1건인데 탭은 2라고 적힌다
		assertThat(sellerOrderService.list(SELLER_KAKAO, null, null, "스탠드", 0, 20)
				.counts().secondUnpaid()).isEqualTo(1);
	}

	@Test
	@DisplayName("판매별_필터는_그_폼이_담긴_주문만_준다")
	void 판매별_필터() {
		String stand = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		place(badgeForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		List<String> filtered = sellerOrderService
				.list(SELLER_KAKAO, null, standForm.getId(), null, 0, 20)
				.items().stream().map(SellerOrderPageResponse.SellerOrderItem::orderNo).toList();

		assertThat(filtered).containsExactly(stand);
	}

	@Test
	@DisplayName("남의_판매_폼으로_거르면_없는_것과_똑같이_404_다")
	void 남의_폼_필터() {
		// 403 이면 "그 id 에 폼이 있다" 가 새어 나가 남의 판매를 훑을 수 있다
		assertThatThrownBy(() -> sellerOrderService.list(SELLER_KAKAO, null, otherForm.getId(), null, 0, 20))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 카드 내용

	@Test
	@DisplayName("폼이_여럿이면_제목이_외_N건으로_접힌다")
	void 제목_접기() {
		OrderGroup group = newGroup();
		addOrder(group, standForm, OrderStatus.PAID);
		addOrder(group, badgeForm, OrderStatus.PAID);
		persist(group, OrderGroupStatus.PAID);

		// 한 번 결제한 장바구니 주문이 여러 줄로 쪼개지면 탭 건수와 청구 대상이 어긋난다
		assertThat(page().items()).hasSize(1);
		assertThat(page().items().get(0).title()).isEqualTo("아크릴 스탠드 — 2차 공구 외 1건");
	}

	@Test
	@DisplayName("수령인_이름을_쓴다_카카오_닉네임이_아니다")
	void 수령인_이름() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(page().items().get(0).buyerName()).isEqualTo("김서연");
	}

	@Test
	@DisplayName("결제_문구가_차수별_상태를_그대로_보여준다")
	void 결제_문구() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);
		payment(orderNo, "FIRST", "CAPTURED", 20000);

		assertThat(page().items().get(0).paymentSummary()).isEqualTo("1차금 완료 · 2차금 미납");
	}

	@Test
	@DisplayName("확인_중인_결제를_실패로_찍지_않는다")
	void 확인_중은_실패가_아니다() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURE_PENDING", 20000);

		// 실패로 찍으면 셀러가 재결제를 요구하고, 이미 출금된 건이면 이중 결제가 된다
		assertThat(page().items().get(0).paymentSummary()).startsWith("1차금 확인 중");
	}

	// ---------------------------------------------------------------- 상세 드로어

	@Test
	@DisplayName("연락처는_가운데를_가려서_나가고_배송지는_담기지_않는다")
	void 상세_마스킹() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		SellerOrderDetailResponse detail = sellerOrderService.detail(SELLER_KAKAO, orderNo);

		assertThat(detail.buyer().maskedPhone()).isEqualTo("010-****-1234");
		assertThat(detail.buyer().recipientName()).isEqualTo("김서연");

		// 원본 주소가 응답 어디에도 실리지 않아야 한다 — 목록을 훑는 내내 나갈 값이 아니다
		assertThat(detail.toString()).doesNotContain("테헤란로");
	}

	@Test
	@DisplayName("상세는_다음_단계와_막힌_이유를_같이_준다")
	void 상세_다음_단계() {
		String blocked = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		String ready = place(badgeForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		assertThat(sellerOrderService.detail(SELLER_KAKAO, blocked).nextStep())
				.satisfies(step -> {
					assertThat(step.available()).isFalse();
					assertThat(step.blockReason()).contains("입고");
				});
		assertThat(sellerOrderService.detail(SELLER_KAKAO, ready).nextStep().available()).isTrue();
	}

	@Test
	@DisplayName("상세에_상품_옵션_스냅샷이_담긴다")
	void 상세_상품() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(sellerOrderService.detail(SELLER_KAKAO, orderNo).items())
				.singleElement()
				.satisfies(line -> {
					assertThat(line.productName()).isEqualTo("상품");
					assertThat(line.optionName()).isEqualTo("옵션 A");
					assertThat(line.qty()).isEqualTo(2);
				});
	}

	@Test
	@DisplayName("남의_주문번호는_없는_것과_똑같이_404_다")
	void 남의_주문_상세() {
		String other = placeFor(otherSeller, otherForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThatThrownBy(() -> sellerOrderService.detail(SELLER_KAKAO, other))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.ORDER_GROUP_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 페이지

	@Test
	@DisplayName("페이지_정보가_맞는다")
	void 페이지() {
		for (int i = 0; i < 3; i++) {
			place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		}
		SellerOrderPageResponse first = sellerOrderService.list(SELLER_KAKAO, null, null, null, 0, 2);
		SellerOrderPageResponse last = sellerOrderService.list(SELLER_KAKAO, null, null, null, 1, 2);

		assertThat(first.page().totalElements()).isEqualTo(3);
		assertThat(first.page().totalPages()).isEqualTo(2);
		assertThat(first.page().hasNext()).isTrue();
		assertThat(last.page().hasNext()).isFalse();
		assertThat(last.items()).hasSize(1);
	}

	@Test
	@DisplayName("페이지_크기는_50을_넘지_않고_0이면_기본값이다")
	void 크기_제한() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(sellerOrderService.list(SELLER_KAKAO, null, null, null, 0, 10_000).page().size())
				.isEqualTo(50);
		assertThat(sellerOrderService.list(SELLER_KAKAO, null, null, null, 0, 0).page().size())
				.isEqualTo(20);
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("목록_응답은_캐시하지_않는다")
	void no_store() throws Exception {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(SELLER_KAKAO, "셀러"));

		// 캐시된 목록을 보고 이미 청구한 건을 또 청구할 수 있다
		mockMvc.perform(get("/seller/orders").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.counts.all").value(1));
	}

	@Test
	@DisplayName("로그인하지_않으면_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/seller/orders"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 도우미

	private SellerOrderPageResponse page() {
		return page((SellerOrderTab) null);
	}

	private SellerOrderPageResponse page(SellerOrderTab tab) {
		return sellerOrderService.list(SELLER_KAKAO, tab, null, null, 0, 20);
	}

	private List<String> orderNos() {
		return orderNos((SellerOrderTab) null);
	}

	private List<String> orderNos(SellerOrderTab tab) {
		return page(tab).items().stream()
				.map(SellerOrderPageResponse.SellerOrderItem::orderNo).toList();
	}

	private List<String> orderNos(String q) {
		return sellerOrderService.list(SELLER_KAKAO, null, null, q, 0, 20).items().stream()
				.map(SellerOrderPageResponse.SellerOrderItem::orderNo).toList();
	}

	private String place(SaleForm form, OrderGroupStatus groupStatus, OrderStatus orderStatus) {
		return placeFor(seller, form, groupStatus, orderStatus);
	}

	private String placeFor(Seller owner, SaleForm form,
	                        OrderGroupStatus groupStatus, OrderStatus orderStatus) {
		OrderGroup group = OrderGroup.create("cs_" + (++tokenSeq) + "_" + System.nanoTime(),
				buyer, owner, 3000);
		addOrder(group, form, orderStatus);
		return persist(group, groupStatus);
	}

	private OrderGroup newGroup() {
		return OrderGroup.create("cs_" + (++tokenSeq) + "_" + System.nanoTime(), buyer, seller, 3000);
	}

	private void addOrder(OrderGroup group, SaleForm form, OrderStatus orderStatus) {
		Product product = form.getProducts().get(0);
		Order order = Order.create(form);
		order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 2));
		group.addOrder(order);
		group.applyShippingFee(3000);

		// 상태는 저장 뒤에 SQL 로 맞춘다 — 엔티티가 전이 순서를 강제한다
		pendingStatuses.add(orderStatus);
	}

	/**
	 * 저장한 뒤 상태를 SQL 로 맞춘다.
	 *
	 * 엔티티가 전이를 강제해서(PAID 를 거치지 않으면 ARRIVED 가 안 된다) 자바로는
	 * 원하는 조합을 만들기 어렵다. 조회를 검증하는 테스트라 결과 상태만 있으면 된다.
	 */
	private String persist(OrderGroup group, OrderGroupStatus groupStatus) {
		orderGroupRepository.saveAndFlush(group);
		group.markPayPending("ord_" + tokenSeq + "_" + System.nanoTime());
		orderGroupRepository.saveAndFlush(group);
		shippingRepository.saveAndFlush(Shipping.snapshotOf(group, address));

		jdbcTemplate.update("UPDATE order_group SET status = ? WHERE id = ?",
				groupStatus.name(), group.getId());

		List<Order> orders = group.getOrders();
		for (int i = 0; i < orders.size(); i++) {
			jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?",
					pendingStatuses.get(i).name(), orders.get(i).getId());
		}
		pendingStatuses.clear();
		return group.getOrderNo();
	}

	private void payment(String orderNo, String phase, String status, int amount) {
		Long groupId = jdbcTemplate.queryForObject(
				"SELECT id FROM order_group WHERE order_no = ?", Long.class, orderNo);

		jdbcTemplate.update("""
						INSERT INTO payment (order_group_id, phase, session_id, amount, tax_free_amount, status)
						VALUES (?, ?, ?, ?, 0, ?)
						""",
				groupId, phase, "pymt_" + System.nanoTime(), amount, status);
	}

	private Seller saveSeller(String kakaoId) {
		Seller saved = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId(kakaoId)
				.storeSlug("store-" + System.nanoTime())
				.shippingFee(3000)
				.build());
		saved.approve();
		return sellerRepository.saveAndFlush(saved);
	}

	private SaleForm saveForm(Seller owner, String title) {
		long unique = System.nanoTime();

		SaleForm form = SaleForm.builder()
				.seller(owner)
				.title(title)
				.slug("form-" + unique)
				.saleType(SaleType.GROUP)
				.stockMax(100)
				.targetQty(1)
				.closesAt(LocalDateTime.now().plusDays(7))
				.minOrderAmount(0)
				.build();

		Product product = Product.builder().name("상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				SaleFormStatus.SELLING.name(), form.getId());
		return form;
	}
}
