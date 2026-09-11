package store.moeum.moeum.payment;

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
import store.moeum.moeum.payment.dto.InProgressOrderResponse;
import store.moeum.moeum.payment.dto.PaymentResultResponse.PendingReason;
import store.moeum.moeum.payment.domain.PaymentPhase;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 잃어버린 orderToken 되찾기 (D-042).
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>남의 결제가 절대 섞이지 않는다</b> — 여기 섞이면 남의 주문 토큰을 넘겨주는 것이고,
 *       토큰만 있으면 상태 조회와 승인이 된다</li>
 *   <li>끝난 · 죽은 건은 들어 있지 않다 — 이어서 결제하라는 화면이 뜨면 안 된다</li>
 *   <li><b>AWAITING_PAYMENT 와 CONFIRMING 이 갈린다</b> — 대응이 정반대다.
 *       합쳐 두면 프론트가 둘 중 하나를 반드시 틀린다</li>
 * </ul>
 */
class InProgressOrderTest extends IntegrationTest {

	private static final String BUYER = "kakao-inprogress-buyer";
	private static final String OTHER = "kakao-inprogress-other";

	@Autowired
	private PaymentService paymentService;

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
	private SaleForm standForm;

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
				.kakaoId("kakao-inprogress-seller")
				.storeSlug("store-" + System.nanoTime())
				.shippingFee(3000)
				.build());
		seller.approve();
		sellerRepository.saveAndFlush(seller);

		standForm = saveForm("아크릴 스탠드 — 2차 공구");
		buyer = buyerRepository.saveAndFlush(Buyer.of(BUYER, "구매자"));
		other = buyerRepository.saveAndFlush(Buyer.of(OTHER, "남"));
	}

	// ---------------------------------------------------------------- 무엇이 들어오나

	@Test
	@DisplayName("결제창을_끝내지_않은_건은_이어서_결제하라고_알려_준다")
	void 결제창_미완료() {
		OrderGroup group = place(buyer, OrderGroupStatus.PAY_PENDING);
		payment(group, PaymentPhase.FIRST, "CREATED", 20000);

		InProgressOrderResponse.InProgressOrder item = items().get(0);

		// 폴링만 해서는 영원히 안 바뀐다. 승인 요청 자체가 오지 않았다
		assertThat(item.orderToken()).isEqualTo(group.getOrderToken());
		assertThat(item.pendingReason()).isEqualTo(PendingReason.AWAITING_PAYMENT);
		assertThat(item.phase()).isEqualTo(PaymentPhase.FIRST);
		assertThat(item.amount()).isEqualTo(20000);
		assertThat(item.title()).isEqualTo("아크릴 스탠드 — 2차 공구");
	}

	@Test
	@DisplayName("승인_결과를_기다리는_건은_기다리라고_알려_준다")
	void 승인_대기() {
		OrderGroup group = place(buyer, OrderGroupStatus.CONFIRMING);
		payment(group, PaymentPhase.FIRST, "CAPTURE_PENDING", 20000);

		// 여기서 다시 결제시키면 이중 결제다. 실제로 출금됐을 수 있다
		assertThat(items().get(0).pendingReason()).isEqualTo(PendingReason.CONFIRMING);
	}

	@Test
	@DisplayName("2차금도_같이_나온다")
	void 이차금() {
		OrderGroup group = place(buyer, OrderGroupStatus.SECOND_PENDING);
		payment(group, PaymentPhase.SECOND, "CREATED", 15000);

		// 구매자에게는 1차금이든 2차금이든 똑같이 "내다 만 결제" 다
		assertThat(items()).singleElement()
				.satisfies(item -> {
					assertThat(item.phase()).isEqualTo(PaymentPhase.SECOND);
					assertThat(item.amount()).isEqualTo(15000);
				});
	}

	@Test
	@DisplayName("폼이_여럿이면_제목이_외_N건으로_접힌다")
	void 대표_제목() {
		SaleForm badgeForm = saveForm("아크릴 뱃지 — 1차 공구");
		OrderGroup group = place(buyer, OrderGroupStatus.PAY_PENDING, standForm, badgeForm);
		payment(group, PaymentPhase.FIRST, "CREATED", 40000);

		assertThat(items().get(0).title()).isEqualTo("아크릴 스탠드 — 2차 공구 외 1건");
	}

	// ---------------------------------------------------------------- 무엇이 빠지나

	@Test
	@DisplayName("끝난_결제는_들어_있지_않다")
	void 끝난_건() {
		OrderGroup paid = place(buyer, OrderGroupStatus.PAID);
		payment(paid, PaymentPhase.FIRST, "CAPTURED", 20000);

		OrderGroup failed = place(buyer, OrderGroupStatus.FAILED);
		payment(failed, PaymentPhase.FIRST, "FAILED", 20000);

		assertThat(items()).isEmpty();
	}

	@Test
	@DisplayName("만료되거나_취소된_묶음은_들어_있지_않다")
	void 죽은_묶음() {
		OrderGroup expired = place(buyer, OrderGroupStatus.EXPIRED);
		payment(expired, PaymentPhase.FIRST, "CREATED", 20000);

		OrderGroup canceled = place(buyer, OrderGroupStatus.CANCELED);
		payment(canceled, PaymentPhase.FIRST, "CREATED", 20000);

		// 이어서 결제하라는 화면이 뜨는데 그 세션은 이미 죽어 있다
		assertThat(items()).isEmpty();
	}

	@Test
	@DisplayName("다른_구매자의_결제는_절대_섞이지_않는다")
	void 격리() {
		OrderGroup mine = place(buyer, OrderGroupStatus.PAY_PENDING);
		payment(mine, PaymentPhase.FIRST, "CREATED", 20000);

		OrderGroup theirs = place(other, OrderGroupStatus.PAY_PENDING);
		payment(theirs, PaymentPhase.FIRST, "CREATED", 20000);

		// 섞이면 남의 orderToken 을 넘겨주는 것이고, 토큰만 있으면 상태 조회와 승인이 된다
		assertThat(items()).singleElement()
				.extracting(InProgressOrderResponse.InProgressOrder::orderToken)
				.isEqualTo(mine.getOrderToken());
	}

	@Test
	@DisplayName("진행_중인_결제가_없으면_빈_목록이고_오류가_아니다")
	void 빈_목록() {
		assertThat(items()).isEmpty();
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("목록은_캐시하지_않는다")
	void 캐시_금지() throws Exception {
		OrderGroup group = place(buyer, OrderGroupStatus.PAY_PENDING);
		payment(group, PaymentPhase.FIRST, "CREATED", 20000);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(BUYER, "구매자"));

		// 방금 끝난 결제가 남아 있으면 이어서 결제하라는 화면이 뜬다
		mockMvc.perform(get("/me/orders/in-progress").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items[0].orderToken").value(group.getOrderToken()))
				.andExpect(jsonPath("$.items[0].pendingReason").value("AWAITING_PAYMENT"));
	}

	@Test
	@DisplayName("비로그인은_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/me/orders/in-progress"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 도우미

	private java.util.List<InProgressOrderResponse.InProgressOrder> items() {
		return paymentService.inProgress(new SessionUser(BUYER, "구매자")).items();
	}

	private OrderGroup place(Buyer owner, OrderGroupStatus status, SaleForm... forms) {
		SaleForm[] target = (forms.length == 0) ? new SaleForm[]{standForm} : forms;
		OrderGroup group = OrderGroup.create("cs_" + System.nanoTime(), owner, seller, 3000);

		for (SaleForm form : target) {
			Product product = form.getProducts().get(0);
			Order order = Order.create(form);
			order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 1));
			group.addOrder(order);
		}
		group.applyShippingFee(3000);

		orderGroupRepository.saveAndFlush(group);
		group.markPayPending("ord_" + System.nanoTime());
		orderGroupRepository.saveAndFlush(group);

		// 상태는 SQL 로 맞춘다 — 엔티티가 전이 순서를 강제한다
		jdbcTemplate.update("UPDATE order_group SET status = ? WHERE id = ?",
				status.name(), group.getId());
		return group;
	}

	private void payment(OrderGroup group, PaymentPhase phase, String status, int amount) {
		jdbcTemplate.update("""
						INSERT INTO payment (order_group_id, phase, session_id, amount, tax_free_amount, status)
						VALUES (?, ?, ?, ?, 0, ?)
						""",
				group.getId(), phase.name(), "pymt_" + System.nanoTime(), amount, status);
	}

	private SaleForm saveForm(String title) {
		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title(title)
				.slug("form-" + System.nanoTime())
				.saleType(SaleType.GROUP)
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
