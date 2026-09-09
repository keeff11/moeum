package store.moeum.moeum.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
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
import store.moeum.moeum.order.dto.SecondChargeResponse;
import store.moeum.moeum.payment.PaymentWriter;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 2차금 일괄 청구 (와이어프레임 S10) + 부분 취소 묶음의 2차금 정산.
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>부분 취소된 묶음도 잔금을 받을 수 있다.</b> 예전에는 취소된 폼이 영원히
 *       ARRIVED 가 아니라서 2차금이 영영 열리지 않았다</li>
 *   <li>청구 금액에 <b>이미 환불한 폼의 잔금이 섞이지 않는다</b></li>
 *   <li>청구는 알림을 다시 낼 뿐 <b>주문 상태를 바꾸지 않는다</b></li>
 *   <li>연타해도 구매자에게 하루 한 번을 넘겨 독촉이 가지 않는다</li>
 * </ul>
 */
class SecondChargeTest extends IntegrationTest {

	private static final String SELLER_KAKAO = "kakao-s10-seller";
	private static final String OTHER_KAKAO = "kakao-s10-other";
	private static final String BUYER_KAKAO = "kakao-s10-buyer";

	@Autowired
	private SecondChargeService secondChargeService;

	@Autowired
	private SellerOrderService sellerOrderService;

	@Autowired
	private PaymentWriter paymentWriter;

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
	private TransactionTemplate tx;

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

	private int seq;
	private final List<OrderStatus> pending = new ArrayList<>();

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
		seq = 0;

		seller = saveSeller(SELLER_KAKAO);
		otherSeller = saveSeller(OTHER_KAKAO);
		standForm = saveForm(seller, "아크릴 스탠드");
		badgeForm = saveForm(seller, "아크릴 뱃지");
		otherForm = saveForm(otherSeller, "남의 판매");

		buyer = buyerRepository.saveAndFlush(Buyer.of(BUYER_KAKAO, "구매자닉네임"));
		address = buyerAddressRepository.saveAndFlush(BuyerAddress.builder()
				.buyer(buyer).recipientName("김서연").phone("01012341234")
				.address1("서울 강남구 테헤란로 1").build());
	}

	// ------------------------------------------------- 부분 취소 묶음 (버그 수정)

	@Test
	@DisplayName("폼_하나가_취소돼도_남은_폼이_입고되면_2차금이_열린다")
	void 부분_취소도_청구_대상이다() {
		OrderGroup group = newGroup();
		add(group, standForm, OrderStatus.ARRIVED);
		add(group, badgeForm, OrderStatus.CANCELED);
		String orderNo = persist(group, OrderGroupStatus.PAID);

		// 취소된 폼까지 입고를 요구하면 그 폼은 영원히 CANCELED 라 조건이 영영 참이 안 된다
		assertThat(secondPaymentDue(orderNo)).isTrue();
		assertThat(preview().targetCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("청구_금액에서_취소된_폼의_잔금이_빠진다")
	void 취소분은_금액에서_빠진다() {
		OrderGroup group = newGroup();
		add(group, standForm, OrderStatus.ARRIVED);
		add(group, badgeForm, OrderStatus.CANCELED);
		persist(group, OrderGroupStatus.PAID);

		// 폼당 2차금 12,000 · 배송비 3,000. 취소분을 빼면 12,000 + 3,000 이다
		assertThat(preview().totalAmount()).isEqualTo(15_000);
	}

	@Test
	@DisplayName("취소로_총액이_줄어도_배송비는_굳은_값_그대로다")
	void 배송비는_되살아나지_않는다() {
		OrderGroup group = newGroup();
		add(group, standForm, OrderStatus.ARRIVED);
		add(group, badgeForm, OrderStatus.CANCELED);
		String orderNo = persist(group, OrderGroupStatus.PAID);

		// 취소했더니 없던 배송비가 생기는 것은 구매자가 납득할 수 없다 (D-032)
		assertThat(reload(orderNo).getShippingFee()).isEqualTo(3_000);
	}

	@Test
	@DisplayName("부분_취소된_묶음도_구매자가_2차금_결제를_시작할_수_있다")
	void 부분_취소도_결제가_열린다() {
		OrderGroup group = newGroup();
		add(group, standForm, OrderStatus.ARRIVED);
		add(group, badgeForm, OrderStatus.CANCELED);
		String orderNo = persist(group, OrderGroupStatus.PAID);
		String orderToken = reload(orderNo).getOrderToken();

		// 목록엔 뜨는데 결제는 SECOND_PAYMENT_NOT_DUE 로 튕기던 자리다
		assertThatCode(() -> paymentWriter.prepareSecond(BUYER_KAKAO, orderToken))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("모든_폼이_취소된_묶음은_대상이_아니다")
	void 전부_취소는_제외() {
		OrderGroup group = newGroup();
		add(group, standForm, OrderStatus.CANCELED);
		String orderNo = persist(group, OrderGroupStatus.PAID);

		// 살아 있는 주문이 없으면 받을 잔금도 없다
		assertThat(secondPaymentDue(orderNo)).isFalse();
		assertThat(preview().targetCount()).isZero();
	}

	// ------------------------------------------------- 대상 집합

	@Test
	@DisplayName("대상_건수가_주문_목록의_2차금_미납_탭과_같다")
	void 탭과_같은_기준이다() {
		place(standForm, OrderStatus.ARRIVED);
		place(badgeForm, OrderStatus.ARRIVED);
		place(standForm, OrderStatus.PAID);        // 입고 전

		// 두 숫자가 어긋나면 화면에 보이는 것과 실제 청구 대상이 달라진다
		assertThat(preview().targetCount())
				.isEqualTo((int) sellerOrderService
						.list(SELLER_KAKAO, SellerOrderTab.SECOND_UNPAID, null, null, 0, 20)
						.counts().secondUnpaid());
	}

	@Test
	@DisplayName("입고_전_주문은_대상이_아니다")
	void 입고_전_제외() {
		place(standForm, OrderStatus.PAID);

		assertThat(preview().targetCount()).isZero();
		assertThat(preview().totalAmount()).isZero();
	}

	@Test
	@DisplayName("다른_셀러의_주문은_대상에_없다")
	void 셀러_격리() {
		placeFor(otherSeller, otherForm, OrderStatus.ARRIVED);

		assertThat(preview().targetCount()).isZero();
	}

	@Test
	@DisplayName("판매별로_좁혀_청구할_수_있다")
	void 판매별_필터() {
		place(standForm, OrderStatus.ARRIVED);
		place(badgeForm, OrderStatus.ARRIVED);

		assertThat(secondChargeService.preview(SELLER_KAKAO, standForm.getId()).targetCount())
				.isEqualTo(1);
	}

	@Test
	@DisplayName("남의_판매_폼으로_청구하면_없는_것과_똑같이_404_다")
	void 남의_폼() {
		assertThatThrownBy(() -> secondChargeService.charge(SELLER_KAKAO, otherForm.getId()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	// ------------------------------------------------- 청구 실행

	@Test
	@DisplayName("청구하면_알림과_이력이_함께_쌓이고_주문_상태는_그대로다")
	void 청구_실행() {
		String orderNo = place(standForm, OrderStatus.ARRIVED);

		SecondChargeResponse.Result result = secondChargeService.charge(SELLER_KAKAO, null);

		assertThat(result.chargedCount()).isEqualTo(1);
		assertThat(result.chargedAmount()).isEqualTo(15_000);
		assertThat(countOutbox()).isEqualTo(1);
		assertThat(countCharges()).isEqualTo(1);

		// 상태를 SECOND_PENDING 으로 올리면 isSecondPaymentDue() 가 false 가 되어 결제가 막힌다
		assertThat(reload(orderNo).getStatus()).isEqualTo(OrderGroupStatus.PAID);
	}

	@Test
	@DisplayName("대상이_0건이면_청구해도_0건이고_오류가_아니다")
	void 대상_없음() {
		SecondChargeResponse.Result result = secondChargeService.charge(SELLER_KAKAO, null);

		assertThat(result.chargedCount()).isZero();
		assertThat(countOutbox()).isZero();
	}

	// ------------------------------------------------- 쿨다운

	@Test
	@DisplayName("연달아_누르면_두_번째는_건너뛴다")
	void 쿨다운() {
		place(standForm, OrderStatus.ARRIVED);
		secondChargeService.charge(SELLER_KAKAO, null);

		SecondChargeResponse.Result again = secondChargeService.charge(SELLER_KAKAO, null);

		// 알림톡은 건당 단가가 있고, 하루에 여러 번 독촉이 가면 차단당한다
		assertThat(again.chargedCount()).isZero();
		assertThat(again.skippedCount()).isEqualTo(1);
		assertThat(countOutbox()).isEqualTo(1);
	}

	@Test
	@DisplayName("하루가_지나면_다시_청구된다")
	void 쿨다운_해제() {
		place(standForm, OrderStatus.ARRIVED);
		secondChargeService.charge(SELLER_KAKAO, null);
		jdbcTemplate.update("UPDATE second_charge SET charged_at = ?",
				LocalDateTime.now().minusHours(25));

		assertThat(secondChargeService.charge(SELLER_KAKAO, null).chargedCount()).isEqualTo(1);
		assertThat(countOutbox()).isEqualTo(2);
		assertThat(countCharges()).isEqualTo(2);
	}

	@Test
	@DisplayName("미리보기가_쿨다운을_반영한다")
	void 미리보기_쿨다운() {
		place(standForm, OrderStatus.ARRIVED);
		place(badgeForm, OrderStatus.ARRIVED);
		secondChargeService.charge(SELLER_KAKAO, standForm.getId());

		SecondChargeResponse.Preview preview = preview();

		assertThat(preview.targetCount()).isEqualTo(2);
		assertThat(preview.chargeableCount()).isEqualTo(1);
		assertThat(preview.lastChargedAt()).isNotNull();
	}

	// ------------------------------------------------- HTTP

	@Test
	@DisplayName("미리보기_경로가_주문번호_자리보다_먼저_잡히고_캐시되지_않는다")
	void 경로_우선순위() throws Exception {
		place(standForm, OrderStatus.ARRIVED);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(SELLER_KAKAO, "셀러"));

		// /{orderNo} 로 잡히면 주문을 못 찾아 404 가 난다
		mockMvc.perform(get("/seller/orders/second-charge").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.targetCount").value(1));
	}

	@Test
	@DisplayName("로그인하지_않으면_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/seller/orders/second-charge"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------- 도우미

	private SecondChargeResponse.Preview preview() {
		return secondChargeService.preview(SELLER_KAKAO, null);
	}

	/** orders 가 지연 로딩이라 트랜잭션 안에서 봐야 한다 */
	private boolean secondPaymentDue(String orderNo) {
		return Boolean.TRUE.equals(tx.execute(st -> reload(orderNo).isSecondPaymentDue()));
	}

	private OrderGroup reload(String orderNo) {
		return orderGroupRepository.findByOrderNo(orderNo).orElseThrow();
	}

	private int countOutbox() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox WHERE event_type = 'SECOND_PAYMENT_DUE'", Integer.class);
	}

	private int countCharges() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM second_charge", Integer.class);
	}

	private String place(SaleForm form, OrderStatus orderStatus) {
		return placeFor(seller, form, orderStatus);
	}

	private String placeFor(Seller owner, SaleForm form, OrderStatus orderStatus) {
		OrderGroup group = OrderGroup.create("cs_" + (++seq) + "_" + System.nanoTime(), buyer, owner, 3000);
		add(group, form, orderStatus);
		return persist(group, OrderGroupStatus.PAID);
	}

	private OrderGroup newGroup() {
		return OrderGroup.create("cs_" + (++seq) + "_" + System.nanoTime(), buyer, seller, 3000);
	}

	private void add(OrderGroup group, SaleForm form, OrderStatus orderStatus) {
		Product product = form.getProducts().get(0);
		Order order = Order.create(form);
		order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 1));
		group.addOrder(order);
		group.applyShippingFee(3000);
		pending.add(orderStatus);
	}

	/** 저장한 뒤 상태를 SQL 로 맞춘다 — 엔티티가 전이 순서를 강제한다 */
	private String persist(OrderGroup group, OrderGroupStatus groupStatus) {
		orderGroupRepository.saveAndFlush(group);
		group.markPayPending("ord_" + seq + "_" + System.nanoTime());
		orderGroupRepository.saveAndFlush(group);
		shippingRepository.saveAndFlush(Shipping.snapshotOf(group, address));

		jdbcTemplate.update("UPDATE order_group SET status = ? WHERE id = ?",
				groupStatus.name(), group.getId());

		List<Order> orders = group.getOrders();
		for (int i = 0; i < orders.size(); i++) {
			jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?",
					pending.get(i).name(), orders.get(i).getId());
		}
		pending.clear();
		return group.getOrderNo();
	}

	private Seller saveSeller(String kakaoId) {
		Seller saved = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId(kakaoId).storeSlug("store-" + System.nanoTime()).shippingFee(3000).build());
		saved.approve();
		return sellerRepository.saveAndFlush(saved);
	}

	private SaleForm saveForm(Seller owner, String title) {
		long unique = System.nanoTime();

		SaleForm form = SaleForm.builder()
				.seller(owner).title(title).slug("form-" + unique)
				.saleType(SaleType.GROUP).stockMax(100).targetQty(1)
				.closesAt(LocalDateTime.now().plusDays(7)).minOrderAmount(0)
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
