package store.moeum.moeum.seller;

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
import store.moeum.moeum.order.SellerOrderService;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderGroupStatus;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.seller.dto.SellerHomeResponse;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 홈 (와이어프레임 G1).
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>홈의 숫자가 G6 탭 배지와 정확히 같다</b> — 갈라지면 셀러가 어느 쪽을 믿을지 모른다.
 *       2차금 미납은 S10 청구 대상 수라 돈이 걸려 있다</li>
 *   <li>남의 판매·주문이 섞이지 않는다</li>
 *   <li>진행 중 판매는 마감이 임박한 것부터다 — D-day 가 이 카드의 존재 이유다</li>
 *   <li>모집 숫자는 진행 현황 공개를 꺼도 <b>주인에게는 보인다</b></li>
 * </ul>
 */
class SellerHomeTest extends IntegrationTest {

	private static final String SELLER_KAKAO = "kakao-g1-seller";
	private static final String OTHER_KAKAO = "kakao-g1-other";

	@Autowired
	private SellerHomeService sellerHomeService;

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
	private SaleForm otherForm;
	private Buyer buyer;
	private BuyerAddress address;

	private int tokenSeq;
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

		seller = saveSeller(SELLER_KAKAO, "모음스토어");
		otherSeller = saveSeller(OTHER_KAKAO, "남의스토어");
		standForm = saveForm(seller, "아크릴 스탠드 — 2차 공구", 7);
		otherForm = saveForm(otherSeller, "남의 판매", 7);

		buyer = buyerRepository.saveAndFlush(Buyer.of("kakao-g1-buyer", "구매자닉네임"));
		address = buyerAddressRepository.saveAndFlush(BuyerAddress.builder()
				.buyer(buyer)
				.recipientName("김서연")
				.phone("01012341234")
				.postalCode("06236")
				.address1("서울 강남구 테헤란로 1")
				.build());
	}

	// ---------------------------------------------------------------- 처리할 주문

	@Test
	@DisplayName("처리할_주문은_2차금_미납과_발송_대기의_합이다")
	void 처리할_주문() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);       // 2차금 미납
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);       // 2차금 미납
		place(standForm, OrderGroupStatus.SECOND_PAID, OrderStatus.PAID);   // 발송 대기
		place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);          // 아직 입고 전이라 할 일이 없다

		SellerHomeResponse.TodoCounts todo = home().todo();

		assertThat(todo.secondUnpaid()).isEqualTo(2);
		assertThat(todo.shippingWaiting()).isEqualTo(1);
		assertThat(todo.total()).isEqualTo(3);
	}

	@Test
	@DisplayName("홈의_숫자는_주문_목록의_탭_배지와_정확히_같다")
	void 목록과_같은_집계() {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);
		place(standForm, OrderGroupStatus.SECOND_PAID, OrderStatus.PAID);
		place(standForm, OrderGroupStatus.CREATED, OrderStatus.CREATED);

		SellerHomeResponse.TodoCounts todo = home().todo();
		SellerOrderPageResponse.TabCounts tabs =
				sellerOrderService.list(SELLER_KAKAO, null, null, null, 0, 20).counts();

		// 두 화면이 다른 숫자를 보이면 어느 쪽을 믿을지 모른다. 게다가 이건 S10 청구 대상 수다
		assertThat(todo.secondUnpaid()).isEqualTo(tabs.secondUnpaid());
		assertThat(todo.shippingWaiting()).isEqualTo(tabs.preparing());
	}

	@Test
	@DisplayName("다른_셀러의_주문은_세지_않는다")
	void 셀러_격리_주문() {
		placeFor(otherSeller, otherForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		assertThat(home().todo().total()).isZero();
		assertThat(home().recentOrders()).isEmpty();
	}

	// ---------------------------------------------------------------- 진행 중 판매

	@Test
	@DisplayName("판매_중과_일시중지가_진행_중_판매다")
	void 진행_중_판매() {
		SaleForm paused = saveForm(seller, "일시중지된 판매", 3);
		setStatus(paused, SaleFormStatus.PAUSED);
		SaleForm closed = saveForm(seller, "마감된 판매", 1);
		setStatus(closed, SaleFormStatus.CLOSED);
		SaleForm draft = saveForm(seller, "작성 중", 1);
		setStatus(draft, SaleFormStatus.DRAFT);

		// 일시중지는 끝난 판매가 아니라 셀러가 손을 대야 하는 판매다. 빼면 멈춰 둔 것을 잊는다
		assertThat(home().activeSales()).extracting(SellerHomeResponse.ActiveSale::title)
				.containsExactly("일시중지된 판매", "아크릴 스탠드 — 2차 공구");
	}

	@Test
	@DisplayName("마감이_임박한_판매가_위에_오고_마감일이_없으면_뒤로_간다")
	void 정렬() {
		SaleForm urgent = saveForm(seller, "내일 마감", 1);
		SaleForm noDeadline = saveForm(seller, "마감일 없음", 7);
		jdbcTemplate.update("UPDATE sale_form SET closes_at = NULL WHERE id = ?", noDeadline.getId());

		// D-day 가 이 카드의 존재 이유다. 급한 것이 아래 있으면 카드를 볼 이유가 없다
		assertThat(home().activeSales()).extracting(SellerHomeResponse.ActiveSale::title)
				.containsExactly("내일 마감", "아크릴 스탠드 — 2차 공구", "마감일 없음");
	}

	@Test
	@DisplayName("D_day는_시각이_아니라_날짜로_센다")
	void d_day() {
		SaleForm today = saveForm(seller, "오늘 마감", 0);
		// 오늘 자정 직전에 끝나도 D-0 이다. 시간 차로 나누면 D-1 로 보인다
		jdbcTemplate.update("UPDATE sale_form SET closes_at = ? WHERE id = ?",
				LocalDate.now().atTime(23, 59, 59), today.getId());

		List<SellerHomeResponse.ActiveSale> sales = home().activeSales();

		assertThat(sales).filteredOn(s -> s.title().equals("오늘 마감"))
				.singleElement()
				.extracting(SellerHomeResponse.ActiveSale::dDay).isEqualTo(0);
		assertThat(sales).filteredOn(s -> s.title().startsWith("아크릴"))
				.singleElement()
				.extracting(SellerHomeResponse.ActiveSale::dDay).isEqualTo(7);
	}

	@Test
	@DisplayName("마감일이_없으면_D_day도_없다")
	void d_day_없음() {
		jdbcTemplate.update("UPDATE sale_form SET closes_at = NULL WHERE id = ?", standForm.getId());

		assertThat(home().activeSales().get(0).dDay()).isNull();
	}

	@Test
	@DisplayName("모집_현황을_감춰도_주인에게는_보인다")
	void 모집_숫자는_주인에게_보인다() {
		jdbcTemplate.update("UPDATE sale_form SET progress_public = FALSE, sold = 68, target_qty = 100 WHERE id = ?",
				standForm.getId());

		// progress_public 은 남에게 감추는 설정이다. 주인 화면까지 가리면 자기 판매를 못 본다
		SellerHomeResponse.ActiveSale sale = home().activeSales().get(0);
		assertThat(sale.recruitedCount()).isEqualTo(68);
		assertThat(sale.recruitTarget()).isEqualTo(100);
		assertThat(sale.targetReached()).isFalse();
	}

	@Test
	@DisplayName("목표를_채우면_표시가_바뀐다")
	void 목표_달성() {
		jdbcTemplate.update("UPDATE sale_form SET sold = 100, target_qty = 100 WHERE id = ?",
				standForm.getId());

		assertThat(home().activeSales().get(0).targetReached()).isTrue();
	}

	@Test
	@DisplayName("다른_셀러의_판매는_섞이지_않는다")
	void 셀러_격리_판매() {
		assertThat(home().activeSales()).extracting(SellerHomeResponse.ActiveSale::title)
				.doesNotContain("남의 판매");
	}

	@Test
	@DisplayName("진행_중인_판매가_없으면_빈_상태이고_오류가_아니다")
	void 빈_상태() {
		setStatus(standForm, SaleFormStatus.CLOSED);

		SellerHomeResponse home = home();

		assertThat(home.activeSales()).isEmpty();
		assertThat(home.recentOrders()).isEmpty();
		assertThat(home.todo().total()).isZero();
		assertThat(home.storeName()).isEqualTo("모음스토어");
	}

	// ---------------------------------------------------------------- 최근 주문

	@Test
	@DisplayName("최근_주문은_다섯_건까지_최신순으로_준다")
	void 최근_주문() {
		for (int i = 0; i < 6; i++) {
			String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
			jdbcTemplate.update("UPDATE order_group SET created_at = ? WHERE order_no = ?",
					LocalDateTime.now().minusDays(6 - i), orderNo);
		}

		assertThat(home().recentOrders()).hasSize(5);
		assertThat(home().recentOrders().get(0).orderedAt())
				.isAfter(home().recentOrders().get(4).orderedAt());
	}

	@Test
	@DisplayName("결제_전_세션은_최근_주문에_없다")
	void 결제_전_세션은_숨긴다() {
		place(standForm, OrderGroupStatus.CREATED, OrderStatus.CREATED);
		String paid = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 15분 뒤 사라질 장바구니 세션이 홈에 쌓이면 안 된다
		assertThat(home().recentOrders()).extracting(SellerOrderPageResponse.SellerOrderItem::orderNo)
				.containsExactly(paid);
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("홈은_캐시하지_않는다")
	void 캐시_금지() throws Exception {
		place(standForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(SELLER_KAKAO, "셀러"));

		// 캐시된 배지를 보면 청구할 것이 남았는데 0으로 보인다
		mockMvc.perform(get("/seller/home").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.todo.secondUnpaid").value(1))
				.andExpect(jsonPath("$.storeName").value("모음스토어"));
	}

	@Test
	@DisplayName("비로그인은_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/seller/home"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 도우미

	private SellerHomeResponse home() {
		return sellerHomeService.home(SELLER_KAKAO);
	}

	private String place(SaleForm form, OrderGroupStatus groupStatus, OrderStatus orderStatus) {
		return placeFor(seller, form, groupStatus, orderStatus);
	}

	private String placeFor(Seller owner, SaleForm form,
	                        OrderGroupStatus groupStatus, OrderStatus orderStatus) {
		OrderGroup group = OrderGroup.create("cs_" + (++tokenSeq) + "_" + System.nanoTime(),
				buyer, owner, 3000);

		Product product = form.getProducts().get(0);
		Order order = Order.create(form);
		order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 2));
		group.addOrder(order);
		group.applyShippingFee(3000);
		pendingStatuses.add(orderStatus);

		return persist(group, groupStatus);
	}

	/**
	 * 저장한 뒤 상태를 SQL 로 맞춘다. 엔티티가 전이를 강제해서
	 * (PAID 를 거치지 않으면 ARRIVED 가 안 된다) 자바로는 원하는 조합을 만들기 어렵다.
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

	private Seller saveSeller(String kakaoId, String storeName) {
		Seller saved = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId(kakaoId)
				.storeSlug("store-" + System.nanoTime())
				.storeName(storeName)
				.shippingFee(3000)
				.build());
		saved.approve();
		return sellerRepository.saveAndFlush(saved);
	}

	/** closesAt 이 며칠 뒤인지로 정렬을 만든다 */
	private SaleForm saveForm(Seller owner, String title, int closesInDays) {
		long unique = System.nanoTime();

		SaleForm form = SaleForm.builder()
				.seller(owner)
				.title(title)
				.slug("form-" + unique)
				.saleType(SaleType.GROUP)
				.stockMax(100)
				.targetQty(100)
				.closesAt(LocalDate.now().plusDays(closesInDays).atTime(12, 0))
				.minOrderAmount(0)
				.build();

		Product product = Product.builder().name("상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);
		setStatus(form, SaleFormStatus.SELLING);
		return form;
	}

	private void setStatus(SaleForm form, SaleFormStatus status) {
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				status.name(), form.getId());
	}
}
