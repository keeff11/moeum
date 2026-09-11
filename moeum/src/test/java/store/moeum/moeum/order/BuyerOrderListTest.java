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
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderGroupStatus;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.order.dto.BuyerOrderStatus;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구매자 주문 목록 (와이어프레임 B13 — 나의 구매 목록).
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>남의 주문이 절대 섞이지 않는다</b> — 섞이면 orderToken 을 통째로 넘겨주는 것이고,
 *       토큰만 있으면 상태 조회와 취소가 된다</li>
 *   <li>결제 전 세션은 주문이 아니다. 취소한 주문은 남는다</li>
 *   <li><b>2차금 미납이 다른 문구에 가려지지 않는다</b> — 구매자가 행동해야 하는 유일한 상태다</li>
 *   <li>진행 단계 배지가 orders.status 를 따라간다 — 셀러 배지로는 이 구분이 사라진다</li>
 * </ul>
 */
class BuyerOrderListTest extends IntegrationTest {

	private static final String BUYER = "kakao-b13-buyer";
	private static final String OTHER = "kakao-b13-other";

	@Autowired
	private BuyerOrderService buyerOrderService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private BuyerRepository buyerRepository;

	@Autowired
	private OrderGroupRepository orderGroupRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	private Seller seller;
	private Buyer buyer;
	private Buyer other;
	private SaleForm groupForm;
	private SaleForm soloForm;

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

		seller = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-b13-seller")
				.storeSlug("store-" + System.nanoTime())
				.shippingFee(3000)
				.build());
		seller.approve();
		sellerRepository.saveAndFlush(seller);

		groupForm = saveForm("아크릴 스탠드", SaleType.GROUP);
		soloForm = saveForm("단독 판매 굿즈", SaleType.SOLO);

		buyer = buyerRepository.saveAndFlush(Buyer.of(BUYER, "구매자"));
		other = buyerRepository.saveAndFlush(Buyer.of(OTHER, "남"));
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("결제_전_세션은_구매_목록에_없다")
	void 결제_전_세션() {
		place(buyer, groupForm, OrderGroupStatus.CREATED, OrderStatus.CREATED);
		place(buyer, groupForm, OrderGroupStatus.EXPIRED, OrderStatus.EXPIRED);
		String paid = place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 15분 뒤 사라질 장바구니 세션이 "내 구매 목록" 에 쌓이면 안 된다
		assertThat(tokens()).containsExactly(paid);
	}

	@Test
	@DisplayName("취소한_주문은_목록에_남는다")
	void 취소_주문() {
		String canceled = place(buyer, groupForm, OrderGroupStatus.CANCELED, OrderStatus.CANCELED);

		// 구매자도 자기가 취소한 내역을 봐야 한다
		assertThat(items()).singleElement().satisfies(item -> {
			assertThat(item.orderToken()).isEqualTo(canceled);
			assertThat(item.status()).isEqualTo(BuyerOrderStatus.CANCELED);
			assertThat(item.note()).isEqualTo("취소됨");
		});
	}

	@Test
	@DisplayName("다른_구매자의_주문은_절대_섞이지_않는다")
	void 격리() {
		String mine = place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		place(other, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		// 섞이면 남의 orderToken 을 넘겨주는 것이고, 토큰만 있으면 취소까지 된다
		assertThat(tokens()).containsExactly(mine);
	}

	@Test
	@DisplayName("최신_주문이_위에_온다")
	void 정렬() {
		String older = place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		jdbcTemplate.update("UPDATE order_group SET created_at = DATE_SUB(NOW(6), INTERVAL 3 DAY)"
				+ " WHERE order_token = ?", older);
		String newer = place(buyer, soloForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(tokens()).containsExactly(newer, older);
	}

	// ---------------------------------------------------------------- 탭

	@Test
	@DisplayName("탭은_판매_유형으로_가른다")
	void 탭() {
		String group = place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		String solo = place(buyer, soloForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(tokens(null)).containsExactlyInAnyOrder(group, solo);
		assertThat(tokens(SaleType.GROUP)).containsExactly(group);
		assertThat(tokens(SaleType.SOLO)).containsExactly(solo);
	}

	// ---------------------------------------------------------------- 상태 배지

	@Test
	@DisplayName("진행_단계가_배지에_그대로_나온다")
	void 진행_단계() {
		place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PRODUCING);

		// 셀러 배지(IN_PROGRESS)로는 제작 중인지 모집 중인지 구분이 사라진다
		assertThat(items().get(0).status()).isEqualTo(BuyerOrderStatus.PRODUCING);
		assertThat(items().get(0).statusLabel()).isEqualTo("제작 중");
	}

	@Test
	@DisplayName("전_폼이_입고되면_2차금_미납이_된다")
	void 이차금_미납() {
		place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		BuyerOrderPageResponse.BuyerOrderItem item = items().get(0);

		assertThat(item.status()).isEqualTo(BuyerOrderStatus.SECOND_UNPAID);
		assertThat(item.statusLabel()).isEqualTo("입고·2차금");
		// 구매자가 행동해야 하는 유일한 상태다. "취소 가능" 으로 덮이면 잔금을 낼 줄 모른다
		assertThat(item.note()).isEqualTo("2차금 미납");
	}

	@Test
	@DisplayName("폼이_여럿이면_가장_덜_진행된_단계를_보여_준다")
	void 가장_덜_진행된_단계() {
		OrderGroup group = newGroup(buyer);
		addOrder(group, groupForm, OrderStatus.ARRIVED);
		addOrder(group, soloForm, OrderStatus.PRODUCING);
		persist(group, OrderGroupStatus.PAID);

		// 구매자가 기다리고 있는 것은 아직 안 된 쪽이다
		assertThat(items().get(0).status()).isEqualTo(BuyerOrderStatus.PRODUCING);
		assertThat(items().get(0).title()).isEqualTo("아크릴 스탠드 외 1건");
	}

	@Test
	@DisplayName("발송까지_끝나면_발송_완료다")
	void 발송_완료() {
		place(buyer, groupForm, OrderGroupStatus.SHIPPED, OrderStatus.SHIPPED);

		assertThat(items().get(0).status()).isEqualTo(BuyerOrderStatus.SHIPPED);
	}

	// ---------------------------------------------------------------- 취소 가능 여부

	@Test
	@DisplayName("공동구매는_발주_전까지_취소할_수_있다")
	void 취소_가능() {
		place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PAID);

		assertThat(items().get(0).cancelable()).isTrue();
		assertThat(items().get(0).note()).isEqualTo("취소 가능");
	}

	@Test
	@DisplayName("발주가_시작되면_취소할_수_없다")
	void 취소_불가() {
		place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.PRODUCING);

		assertThat(items().get(0).cancelable()).isFalse();
		assertThat(items().get(0).note()).isEqualTo("취소 불가");
	}

	@Test
	@DisplayName("한_폼이라도_취소_불가면_취소_불가다")
	void 하나라도_막히면() {
		OrderGroup group = newGroup(buyer);
		addOrder(group, groupForm, OrderStatus.PAID);        // 취소 가능
		addOrder(group, soloForm, OrderStatus.SHIPPED);      // 단독 발송 후라 불가
		persist(group, OrderGroupStatus.PAID);

		// "취소 가능" 을 보여 줬다가 눌렀을 때 거절되는 편이 나쁘다
		assertThat(items().get(0).cancelable()).isFalse();
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("목록은_캐시하지_않는다")
	void 캐시_금지() throws Exception {
		place(buyer, groupForm, OrderGroupStatus.PAID, OrderStatus.ARRIVED);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(BUYER, "구매자"));

		// 지난 값을 보여 주면 이미 낸 잔금을 또 내라고 하거나 못 하는 취소 버튼을 띄운다
		mockMvc.perform(get("/me/orders").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].status").value("SECOND_UNPAID"))
				.andExpect(jsonPath("$.items[0].note").value("2차금 미납"));
	}

	@Test
	@DisplayName("비로그인은_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/me/orders"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 도우미

	private List<BuyerOrderPageResponse.BuyerOrderItem> items() {
		return buyerOrderService.list(BUYER, null, 0, 20).items();
	}

	private List<String> tokens() {
		return tokens(null);
	}

	private List<String> tokens(SaleType saleType) {
		return buyerOrderService.list(BUYER, saleType, 0, 20).items().stream()
				.map(BuyerOrderPageResponse.BuyerOrderItem::orderToken)
				.toList();
	}

	private String place(Buyer owner, SaleForm form,
	                     OrderGroupStatus groupStatus, OrderStatus orderStatus) {
		OrderGroup group = newGroup(owner);
		addOrder(group, form, orderStatus);
		return persist(group, groupStatus);
	}

	private OrderGroup newGroup(Buyer owner) {
		return OrderGroup.create("cs_" + System.nanoTime(), owner, seller, 3000);
	}

	private void addOrder(OrderGroup group, SaleForm form, OrderStatus orderStatus) {
		Product product = form.getProducts().get(0);
		Order order = Order.create(form);
		order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 1));
		group.addOrder(order);
		group.applyShippingFee(3000);

		// 상태는 저장 뒤에 SQL 로 맞춘다 — 엔티티가 전이 순서를 강제한다
		pendingStatuses.add(orderStatus);
	}

	private String persist(OrderGroup group, OrderGroupStatus groupStatus) {
		orderGroupRepository.saveAndFlush(group);
		group.markPayPending("ord_" + System.nanoTime());
		orderGroupRepository.saveAndFlush(group);

		jdbcTemplate.update("UPDATE order_group SET status = ? WHERE id = ?",
				groupStatus.name(), group.getId());

		List<Order> orders = group.getOrders();
		for (int i = 0; i < orders.size(); i++) {
			jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?",
					pendingStatuses.get(i).name(), orders.get(i).getId());
		}
		pendingStatuses.clear();
		return group.getOrderToken();
	}

	private SaleForm saveForm(String title, SaleType saleType) {
		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title(title)
				.slug("form-" + System.nanoTime())
				.saleType(saleType)
				.stockMax(100)
				.targetQty(100)
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
