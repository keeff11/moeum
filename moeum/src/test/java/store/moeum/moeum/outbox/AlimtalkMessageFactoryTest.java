package store.moeum.moeum.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerAddress;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.outbox.infra.SolapiFailedException;
import store.moeum.moeum.outbox.infra.SolapiProperties;
import store.moeum.moeum.outbox.infra.SolapiSendRequest;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * outbox 한 줄 → 알림톡 한 통.
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>승인 안 난 템플릿은 조용히 넘어간다</b> — 예외로 올리면 8회 재시도 뒤 DEAD 로
 *       쌓여서 "아직 승인 전" 이 장애처럼 보인다</li>
 *   <li>금액은 그때 청구한 값이다. 부분 취소 뒤 다시 계산하면 문구가 달라진다</li>
 *   <li>결제일시는 사실이 일어난 시각이다 — 재시도로 며칠 뒤 나가도 그때 시각이어야 한다</li>
 *   <li>수신번호에 하이픈이 남으면 접수되지 않는다</li>
 * </ul>
 */
@TestPropertySource(properties = {
		"moeum.notify.provider=solapi",
		"moeum.notify.solapi.api-key=TEST_KEY",
		"moeum.notify.solapi.api-secret=test-secret",
		"moeum.notify.solapi.pf-id=KA01PF000000000000000000000000",
		"moeum.notify.solapi.from=0212345678",
		"moeum.notify.solapi.link-base=https://www.moeum.store",
		"moeum.notify.solapi.templates.ORDER_PAID=KA01TP000000000000000000000000"
})
class AlimtalkMessageFactoryTest extends IntegrationTest {

	private static final String TEMPLATE_ID = "KA01TP000000000000000000000000";

	@Autowired
	private AlimtalkMessageFactory factory;

	@Autowired
	private SolapiProperties properties;

	@Autowired
	private PlatformTransactionManager transactionManager;

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

	private Seller seller;
	private Buyer buyer;
	private BuyerAddress address;
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

		seller = sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-alimtalk-seller")
				.storeSlug("store-" + System.nanoTime())
				.shippingFee(3000)
				.build());
		seller.approve();
		sellerRepository.saveAndFlush(seller);

		standForm = saveForm("아크릴 스탠드 — 2차 공구");

		buyer = buyerRepository.saveAndFlush(Buyer.of("kakao-alimtalk-buyer", "카카오닉네임"));
		address = buyerAddressRepository.saveAndFlush(BuyerAddress.builder()
				.buyer(buyer)
				.recipientName("김서연")
				.phone("010-1234-5678")
				.postalCode("06236")
				.address1("서울 강남구 테헤란로 1")
				.build());
	}

	// ---------------------------------------------------------------- 변수 채우기

	@Test
	@DisplayName("결제완료_템플릿의_변수_다섯_개를_채운다")
	void 변수() {
		OrderGroup group = placeAndShip();
		LocalDateTime paidAt = LocalDateTime.of(2026, 9, 10, 10, 6);

		Map<String, String> variables = create(group, OutboxEventType.ORDER_PAID, 20000, paidAt)
				.orElseThrow().kakaoOptions().variables();

		assertThat(variables).containsOnlyKeys(
				"#{userName}", "#{goodsName}", "#{prepayment}", "#{billTime}", "#{LINK}");
		assertThat(variables.get("#{userName}")).isEqualTo("김서연");
		assertThat(variables.get("#{goodsName}")).isEqualTo("아크릴 스탠드 — 2차 공구");
		assertThat(variables.get("#{prepayment}")).isEqualTo("20,000");
		assertThat(variables.get("#{billTime}")).isEqualTo("2026년 9월 10일 10:06");
		assertThat(variables.get("#{LINK}"))
				.isEqualTo("https://www.moeum.store/orders/" + group.getOrderToken());
	}

	@Test
	@DisplayName("폼이_여럿이면_제목이_외_N건으로_접힌다")
	void 대표_제목() {
		SaleForm badgeForm = saveForm("아크릴 뱃지 — 1차 공구");
		OrderGroup group = placeAndShip(standForm, badgeForm);

		// 셀러 주문 목록(G6)이 쓰는 문구와 같아야 한다. 갈라지면 구매자가 받은 알림과
		// 셀러가 보는 목록의 제목이 다르다
		assertThat(create(group, OutboxEventType.ORDER_PAID, 40000, LocalDateTime.now())
				.orElseThrow().kakaoOptions().variables().get("#{goodsName}"))
				.isEqualTo("아크릴 스탠드 — 2차 공구 외 1건");
	}

	@Test
	@DisplayName("금액은_그때_청구한_값이지_지금_다시_센_값이_아니다")
	void 금액_스냅샷() {
		OrderGroup group = placeAndShip();

		// 부분 취소 뒤 다시 계산하면 알림 문구가 그때 결제한 금액과 달라진다
		assertThat(create(group, OutboxEventType.ORDER_PAID, 17500, LocalDateTime.now())
				.orElseThrow().kakaoOptions().variables().get("#{prepayment}"))
				.isEqualTo("17,500");
	}

	@Test
	@DisplayName("수신번호에서_하이픈을_뗀다")
	void 수신번호() {
		OrderGroup group = placeAndShip();

		// 하이픈이 남으면 SOLAPI 가 접수하지 않는다
		assertThat(create(group, OutboxEventType.ORDER_PAID, 20000, LocalDateTime.now())
				.orElseThrow().to())
				.isEqualTo("01012345678");
	}

	@Test
	@DisplayName("배송지_스냅샷이_없으면_현재_배송지에서_가져온다")
	void 스냅샷_없음() {
		OrderGroup group = place();   // shipping 을 만들지 않는다 — 이 기능 이전 주문이 그렇다

		SolapiSendRequest.Message message =
				create(group, OutboxEventType.ORDER_PAID, 20000, LocalDateTime.now()).orElseThrow();

		assertThat(message.to()).isEqualTo("01012345678");
		// 스냅샷이 없으면 수령인 이름도 없다. 카카오 닉네임으로 대신한다
		assertThat(message.kakaoOptions().variables().get("#{userName}")).isEqualTo("카카오닉네임");
	}

	// ---------------------------------------------------------------- 갈림길

	@Test
	@DisplayName("승인된_템플릿이_없는_이벤트는_보내지_않는다")
	void 템플릿_없음() {
		OrderGroup group = placeAndShip();

		// 예외로 올리면 8회 재시도 뒤 DEAD 로 쌓인다 — 승인을 기다리는 것은 장애가 아니다
		assertThat(create(group, OutboxEventType.SECOND_PAYMENT_DUE, 12000, LocalDateTime.now()))
				.isEmpty();
		assertThat(create(group, OutboxEventType.REFUND_COMPLETED, 20000, LocalDateTime.now()))
				.isEmpty();
	}

	@Test
	@DisplayName("테스트_수신번호가_걸려_있으면_구매자에게_가지_않는다")
	void 테스트_수신번호() {
		OrderGroup group = placeAndShip();

		// 수신번호 정책이 정해지기 전까지 알림톡을 켜 둘 수 있는 유일한 방법이다.
		// 이게 깨지면 선물 주문의 결제 알림이 수령인에게 간다
		//
		// 직접 만든 팩토리는 빈이 아니라 @Transactional 프록시를 타지 않는다 —
		// 이 클래스를 따로 뺀 이유 그대로라, 여기서는 트랜잭션을 손으로 연다
		String to = new TransactionTemplate(transactionManager).execute(status ->
				overriding("010-9073-6864")
						.create(outbox(group, OutboxEventType.ORDER_PAID, 20000, LocalDateTime.now()))
						.orElseThrow().to());

		assertThat(to).isEqualTo("01090736864");
	}

	@Test
	@DisplayName("배송지가_아예_없으면_확정_실패다")
	void 수신번호_없음() {
		OrderGroup group = place();
		buyerAddressRepository.delete(address);
		buyerAddressRepository.flush();

		// 보낼 곳이 없다. 재시도해도 같으므로 사람이 봐야 한다
		assertThatThrownBy(() -> create(group, OutboxEventType.ORDER_PAID, 20000, LocalDateTime.now()))
				.isInstanceOf(SolapiFailedException.class);
	}

	@Test
	@DisplayName("사라진_주문을_가리키면_확정_실패다")
	void 주문_없음() {
		OutboxMessage message = new OutboxMessage(1L, OutboxAggregate.ORDER_GROUP, 999999L,
				OutboxEventType.ORDER_PAID, "{\"amount\":20000}", 0, LocalDateTime.now());

		assertThatThrownBy(() -> factory.create(message))
				.isInstanceOf(SolapiFailedException.class);
	}

	// ---------------------------------------------------------------- 도우미

	private Optional<SolapiSendRequest.Message> create(OrderGroup group, OutboxEventType eventType,
	                                                   int amount, LocalDateTime createdAt) {
		return factory.create(outbox(group, eventType, amount, createdAt));
	}

	private OutboxMessage outbox(OrderGroup group, OutboxEventType eventType,
	                             int amount, LocalDateTime createdAt) {
		String payload = """
				{"orderToken":"%s","buyerId":%d,"amount":%d}
				""".formatted(group.getOrderToken(), buyer.getId(), amount);

		return new OutboxMessage(1L, OutboxAggregate.ORDER_GROUP, group.getId(),
				eventType, payload, 0, createdAt);
	}

	/** 테스트 수신번호만 바꾼 팩토리. 설정이 record 라 통째로 다시 만든다 */
	private AlimtalkMessageFactory overriding(String testRecipient) {
		SolapiProperties overridden = new SolapiProperties(
				properties.baseUrl(), properties.apiKey(), properties.apiSecret(),
				properties.pfId(), properties.from(), properties.linkBase(), testRecipient,
				properties.templates(), properties.connectTimeout(), properties.readTimeout());

		return new AlimtalkMessageFactory(orderGroupRepository, shippingRepository,
				buyerAddressRepository, overridden);
	}

	private OrderGroup place() {
		return place(standForm);
	}

	private OrderGroup place(SaleForm... forms) {
		OrderGroup group = OrderGroup.create("cs_" + System.nanoTime(), buyer, seller, 3000);

		for (SaleForm form : forms) {
			Product product = form.getProducts().get(0);
			Order order = Order.create(form);
			order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 1));
			group.addOrder(order);
		}
		group.applyShippingFee(3000);

		orderGroupRepository.saveAndFlush(group);
		group.markPayPending("ord_" + System.nanoTime());
		return orderGroupRepository.saveAndFlush(group);
	}

	private OrderGroup placeAndShip(SaleForm... forms) {
		OrderGroup group = (forms.length == 0) ? place() : place(forms);
		shippingRepository.saveAndFlush(Shipping.snapshotOf(group, address));
		return group;
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

		return saleFormRepository.saveAndFlush(form);
	}
}
