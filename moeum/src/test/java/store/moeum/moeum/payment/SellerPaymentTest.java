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
import store.moeum.moeum.buyer.domain.BuyerAddress;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.buyer.domain.BuyerRefundAccount;
import store.moeum.moeum.buyer.domain.BuyerRefundAccountRepository;
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
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.payment.domain.SellerPaymentStatus;
import store.moeum.moeum.payment.dto.SellerPaymentDetailResponse;
import store.moeum.moeum.payment.dto.SellerPaymentPageResponse;
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
 * 셀러 결제/정산 탭 (와이어프레임 G10 · G10 상세 · S14) — D-059.
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>칩 숫자와 목록이 절대 갈라지지 않는다.</b> 판정문이 SQL 과 자바 두 군데 있어
 *       어긋나면 '취소 완료' 칩을 눌렀는데 '결제 완료' 줄이 나온다</li>
 *   <li>남의 결제가 목록에도 상세에도 취소에도 섞이지 않는다</li>
 *   <li><b>셀러 취소가 구매자보다 넓지 않다.</b> 발주가 나간 공구는 셀러도 못 되돌린다</li>
 *   <li>정산 후 직접 환불은 멱등하고, 표시하면 주문이 실제로 취소로 내려간다</li>
 * </ul>
 */
class SellerPaymentTest extends IntegrationTest {

	private static final String SELLER_KAKAO = "kakao-g10-seller";
	private static final String OTHER_KAKAO = "kakao-g10-other";

	@Autowired
	private SellerPaymentService sellerPaymentService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private BuyerRepository buyerRepository;

	@Autowired
	private BuyerAddressRepository buyerAddressRepository;

	@Autowired
	private BuyerRefundAccountRepository refundAccountRepository;

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
				"cart_item", "cart", "wishlist", "buyer_address", "buyer_refund_account", "buyer",
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

		buyer = buyerRepository.saveAndFlush(Buyer.of("kakao-g10-buyer", "구매자닉네임"));
		address = buyerAddressRepository.saveAndFlush(BuyerAddress.builder()
				.buyer(buyer)
				.recipientName("김서연")
				.phone("01012341234")
				.postalCode("06236")
				.address1("서울 강남구 테헤란로 1")
				.build());
		refundAccountRepository.saveAndFlush(BuyerRefundAccount.builder()
				.buyer(buyer)
				.bank("카카오뱅크")
				.accountNo("3333-01-1234567")
				.holderName("김서연")
				.build());
	}

	// ---------------------------------------------------------------- 노출 범위

	@Test
	@DisplayName("한_주문의_1차금과_2차금이_두_줄로_나온다")
	void 차수별_한_줄() {
		String orderNo = place(standForm, OrderGroupStatus.SECOND_PAID, OrderStatus.ARRIVED);
		payment(orderNo, "FIRST", "CAPTURED", 20000);
		payment(orderNo, "SECOND", "CAPTURED", 15000);

		// 묶음으로 접으면 화면의 '구분 · 1차금' 칸을 채울 방법이 없다
		assertThat(paymentNos()).containsExactlyInAnyOrder(orderNo + "-1", orderNo + "-2");
		assertThat(page().counts().all()).isEqualTo(2);
	}

	@Test
	@DisplayName("결제창까지_가지_않은_세션과_만료된_묶음은_내역이_아니다")
	void 결제_전은_숨긴다() {
		String created = place(standForm, OrderGroupStatus.PAY_PENDING, OrderStatus.CREATED);
		payment(created, "FIRST", "CREATED", 20000);

		String expired = place(standForm, OrderGroupStatus.EXPIRED, OrderStatus.EXPIRED);
		payment(expired, "FIRST", "FAILED", 20000);

		String paid = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(paid, "FIRST", "CAPTURED", 20000);

		assertThat(paymentNos()).containsExactly(paid + "-1");
	}

	@Test
	@DisplayName("다른_셀러의_결제는_섞이지_않는다")
	void 셀러_격리() {
		String mine = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(mine, "FIRST", "CAPTURED", 20000);

		String other = placeFor(otherSeller, otherForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(other, "FIRST", "CAPTURED", 20000);

		assertThat(paymentNos()).containsExactly(mine + "-1");

		// 남의 번호를 알아도 "없음" 으로 답한다 — 403 이면 그 번호에 결제가 있다는 것이 샌다
		assertThatThrownBy(() -> sellerPaymentService.detail(SELLER_KAKAO, other + "-1"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
	}

	@Test
	@DisplayName("모양이_틀린_결제번호는_없는_번호와_같이_다룬다")
	void 잘못된_번호() {
		for (String bad : new String[]{"ORD-260828-92", "ORD-260828-92-9", "-1", "이상한값"}) {
			assertThatThrownBy(() -> sellerPaymentService.detail(SELLER_KAKAO, bad))
					.isInstanceOf(BusinessException.class)
					.extracting(e -> ((BusinessException) e).errorCode())
					.isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
		}
	}

	// ---------------------------------------------------------------- 칩

	@Test
	@DisplayName("칩_숫자와_목록이_한_건도_어긋나지_않는다")
	void 칩과_목록이_같다() {
		everyChip();

		SellerPaymentPageResponse all = page();
		assertThat(all.counts().all()).isEqualTo(6);

		// 판정문이 SQL(목록·칩)과 자바(줄 배지) 두 군데 있다. 갈라지면
		// '취소 완료' 칩을 눌렀는데 '결제 완료' 배지가 달린 줄이 나온다
		for (SellerPaymentStatus status : SellerPaymentStatus.values()) {
			SellerPaymentPageResponse page = page(status);

			assertThat(page.items()).hasSize((int) countOf(all, status));
			assertThat(page.items()).allSatisfy(item ->
					assertThat(item.status()).isEqualTo(status));
		}
	}

	@Test
	@DisplayName("칩마다_한_건씩_잡힌다")
	void 칩_건수() {
		everyChip();
		SellerPaymentPageResponse.ChipCounts counts = page().counts();

		assertThat(counts.paid()).isEqualTo(1);
		assertThat(counts.settled()).isEqualTo(1);
		assertThat(counts.canceling()).isEqualTo(1);
		assertThat(counts.canceled()).isEqualTo(1);
		assertThat(counts.failed()).isEqualTo(1);

		// 칩이 없는 '확인 중'은 전체에만 섞여 있다 — 칩 숫자의 합이 all 보다 작은 게 정상이다
		assertThat(counts.pending()).isEqualTo(1);
	}

	@Test
	@DisplayName("승인_결과를_모르는_건을_실패로_접지_않는다")
	void 미확정은_실패가_아니다() {
		String orderNo = place(standForm, OrderGroupStatus.CONFIRMING, OrderStatus.CREATED);
		payment(orderNo, "FIRST", "CAPTURE_PENDING", 20000);

		// 실패라고 부르면 셀러가 안 들어온 돈으로 알고 움직인다 (CLAUDE.md 규칙 3)
		assertThat(page().items().get(0).status()).isEqualTo(SellerPaymentStatus.PENDING);
		assertThat(page().counts().failed()).isZero();
	}

	@Test
	@DisplayName("취소_처리중이_정산_완료보다_먼저다")
	void 판정_우선순위() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		long paymentId = payment(orderNo, "FIRST", "CAPTURED", 20000);
		refund(paymentId, "FAILED", true, null, 20000);
		refund(paymentId, "PROCESSING", false, null, 20000);

		// 결과를 모르는 건이 하나라도 있으면 셀러가 또 손대면 안 된다
		assertThat(page().items().get(0).status()).isEqualTo(SellerPaymentStatus.CANCELING);
	}

	// ---------------------------------------------------------------- 검색 · 필터

	@Test
	@DisplayName("주문번호_수령인_상품명_판매제목으로_찾는다")
	void 검색() {
		String stand = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(stand, "FIRST", "CAPTURED", 20000);
		String badge = place(badgeForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(badge, "FIRST", "CAPTURED", 20000);

		assertThat(search(stand)).containsExactly(stand + "-1");
		assertThat(search("김서")).hasSize(2);
		assertThat(search("스탠드")).containsExactly(stand + "-1");
		assertThat(search("없는말")).isEmpty();
	}

	@Test
	@DisplayName("검색어의_와일드카드는_글자로_취급한다")
	void 와일드카드_이스케이프() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURED", 20000);

		// escape 하지 않으면 % 하나로 결제 전체가 걸린다
		assertThat(search("%")).isEmpty();
		assertThat(search("_")).isEmpty();
	}

	@Test
	@DisplayName("검색_결과에_맞춰_칩_숫자도_줄어든다")
	void 검색과_칩_숫자() {
		String stand = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(stand, "FIRST", "CAPTURED", 20000);
		String badge = place(badgeForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(badge, "FIRST", "CAPTURED", 20000);

		// 칩이 검색을 무시하면 목록은 1건인데 칩은 2라고 적힌다
		assertThat(sellerPaymentService.list(SELLER_KAKAO, null, null, "스탠드", 0, 20)
				.counts().paid()).isEqualTo(1);
	}

	@Test
	@DisplayName("남의_판매_폼으로_거르면_없는_것과_똑같이_404_다")
	void 남의_폼_필터() {
		assertThatThrownBy(() ->
				sellerPaymentService.list(SELLER_KAKAO, null, otherForm.getId(), null, 0, 20))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 상세 · 취소

	@Test
	@DisplayName("상세가_드로어의_여덟_칸을_채운다")
	void 상세_내용() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURED", 32000);

		SellerPaymentDetailResponse detail = detail(orderNo + "-1");

		assertThat(detail.paymentNo()).isEqualTo(orderNo + "-1");
		assertThat(detail.title()).isEqualTo("아크릴 스탠드 — 2차 공구");
		assertThat(detail.buyerName()).isEqualTo("김서연");
		assertThat(detail.phaseLabel()).isEqualTo("1차금");
		assertThat(detail.amount()).isEqualTo(32000);
		assertThat(detail.statusLabel()).isEqualTo("결제 완료");
		assertThat(detail.items()).singleElement().satisfies(line -> {
			assertThat(line.productName()).isEqualTo("상품");
			assertThat(line.optionName()).isEqualTo("옵션 A");
			assertThat(line.qty()).isEqualTo(2);
		});

		// point3 가 승인 응답에 수단을 주지 않는다. 없는 값을 지어내지 않는다
		assertThat(detail.method()).isNull();
	}

	@Test
	@DisplayName("발주가_나간_공구는_셀러도_되돌릴_수_없다")
	void 셀러_취소는_구매자보다_넓지_않다() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PRODUCING);
		payment(orderNo, "FIRST", "CAPTURED", 20000);

		SellerPaymentDetailResponse.CancelSection cancel = detail(orderNo + "-1").cancel();

		assertThat(cancel.cancelable()).isFalse();
		assertThat(cancel.blockedReason()).contains("발주");
		assertThat(cancel.refundableAmount()).isZero();

		// 화면이 버튼을 껐는데도 눌렀다면 여기서 막힌다. point3 로는 아무것도 나가지 않는다
		assertThatThrownBy(() -> sellerPaymentService.cancel(SELLER_KAKAO, orderNo + "-1", null, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_ALLOWED);
	}

	@Test
	@DisplayName("취소할_수_있는_건은_버튼을_켜고_돌아갈_금액을_준다")
	void 취소_가능() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURED", 43000);

		SellerPaymentDetailResponse.CancelSection cancel = detail(orderNo + "-1").cancel();

		if (cancel.blockedUntil() != null) {
			// 23:30~00:30 에 돌린 경우다. 그때는 끄는 것이 맞다
			assertThat(cancel.cancelable()).isFalse();
			return;
		}
		assertThat(cancel.cancelable()).isTrue();

		// 상품값(20000 × 2) 만이다. 배송비 3000 은 이 묶음에서 2차금에 붙는데(D-046)
		// 아직 청구되지 않았다 — 받은 적 없는 배송비를 돌려줄 금액에 세면 과다 환불이 된다
		assertThat(cancel.refundableAmount()).isEqualTo(40000);
	}

	@Test
	@DisplayName("남의_결제는_취소도_막힌다")
	void 남의_결제_취소() {
		String other = placeFor(otherSeller, otherForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(other, "FIRST", "CAPTURED", 20000);

		assertThatThrownBy(() -> sellerPaymentService.cancel(SELLER_KAKAO, other + "-1", null, null))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.ORDER_GROUP_NOT_FOUND);
	}

	// ---------------------------------------------------------------- S14

	@Test
	@DisplayName("정산_후_환불_접수건은_정산_완료_칩에_잡히고_계좌를_준다")
	void 환불_대기() {
		String orderNo = settledManualCase();

		SellerPaymentDetailResponse detail = detail(orderNo + "-1");

		assertThat(detail.status()).isEqualTo(SellerPaymentStatus.SETTLED);
		assertThat(page().items().get(0).needsManualRefund()).isTrue();

		SellerPaymentDetailResponse.ManualRefundSection manual = detail.manualRefund();
		assertThat(manual).isNotNull();
		assertThat(manual.completed()).isFalse();
		assertThat(manual.statusLabel()).isEqualTo("환불 대기");
		assertThat(manual.notice()).isEqualTo("정산 후에는 구매자에게 직접 이체해야 해요");

		// 이체하려면 전체 번호가 필요하다. 여기가 전체 번호가 나가는 유일한 자리다
		assertThat(manual.account().accountNo()).isEqualTo("3333-01-1234567");
		assertThat(manual.account().bank()).isEqualTo("카카오뱅크");
	}

	@Test
	@DisplayName("환불_완료로_바꾸면_주문이_취소로_내려가고_계좌는_다시_가려진다")
	void 환불_완료() {
		String orderNo = settledManualCase();

		assertThat(sellerPaymentService.completeManualRefund(SELLER_KAKAO, orderNo + "-1")).isTrue();

		SellerPaymentDetailResponse after = detail(orderNo + "-1");
		assertThat(after.status()).isEqualTo(SellerPaymentStatus.CANCELED);
		assertThat(after.manualRefund().completed()).isTrue();
		assertThat(after.manualRefund().statusLabel()).isEqualTo("환불 완료");

		// 끝난 건의 번호가 목록을 훑을 때마다 계속 내려갈 이유가 없다
		assertThat(after.manualRefund().account()).isNull();

		// 시스템 취소와 뒷정리가 같아야 한다. 갈라지면 환불은 받았는데 주문이 살아 있는 건이 남는다
		assertThat(orderStatus(orderNo)).isEqualTo(OrderStatus.CANCELED.name());
	}

	@Test
	@DisplayName("두_번_눌러도_재고가_두_번_돌아가지_않는다")
	void 환불_완료는_멱등하다() {
		String orderNo = settledManualCase();
		int soldBefore = sold(standForm);

		assertThat(sellerPaymentService.completeManualRefund(SELLER_KAKAO, orderNo + "-1")).isTrue();
		int soldAfter = sold(standForm);

		// 두 번째는 아무것도 하지 않는다 — 대기 건이 남아 있지 않으므로 404 다
		assertThatThrownBy(() -> sellerPaymentService.completeManualRefund(SELLER_KAKAO, orderNo + "-1"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_FOUND);

		assertThat(soldAfter).isEqualTo(soldBefore - 2);
		assertThat(sold(standForm)).isEqualTo(soldAfter);
	}

	@Test
	@DisplayName("접수된_적_없는_결제는_환불_처리_대상이_아니다")
	void 대상이_아닌_건() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURED", 20000);

		assertThat(detail(orderNo + "-1").manualRefund()).isNull();
		assertThatThrownBy(() -> sellerPaymentService.completeManualRefund(SELLER_KAKAO, orderNo + "-1"))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_NOT_FOUND);
	}

	// ---------------------------------------------------------------- HTTP

	@Test
	@DisplayName("목록_응답은_캐시하지_않는다")
	void no_store() throws Exception {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(orderNo, "FIRST", "CAPTURED", 20000);

		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionKeys.LOGIN_USER, new SessionUser(SELLER_KAKAO, "셀러"));

		// 캐시된 목록을 보고 이미 취소된 건을 또 취소하려 들 수 있다
		mockMvc.perform(get("/seller/payments").session(session))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.counts.all").value(1))
				.andExpect(jsonPath("$.items[0].statusLabel").value("결제 완료"));
	}

	@Test
	@DisplayName("로그인하지_않으면_401_이다")
	void 비로그인() throws Exception {
		mockMvc.perform(get("/seller/payments"))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 도우미

	/** 칩 여섯 종류를 한 건씩 만든다 */
	private void everyChip() {
		String paid = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		payment(paid, "FIRST", "CAPTURED", 20000);

		settledManualCase();

		String canceling = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		refund(payment(canceling, "FIRST", "CAPTURED", 20000), "PROCESSING", false, null, 20000);

		String canceled = place(standForm, OrderGroupStatus.CANCELED, OrderStatus.CANCELED);
		refund(payment(canceled, "FIRST", "CAPTURED", 20000), "COMPLETED", false, null, 20000);

		String failed = place(standForm, OrderGroupStatus.FAILED, OrderStatus.CREATED);
		payment(failed, "FIRST", "FAILED", 20000);

		String pending = place(standForm, OrderGroupStatus.CONFIRMING, OrderStatus.CREATED);
		payment(pending, "FIRST", "CAPTURE_PENDING", 20000);
	}

	/** 정산이 끝나 시스템 취소가 거절된 건 — S14 의 입구다 */
	private String settledManualCase() {
		String orderNo = place(standForm, OrderGroupStatus.PAID, OrderStatus.PAID);
		long paymentId = payment(orderNo, "FIRST", "CAPTURED", 20000);
		refund(paymentId, "FAILED", true, null, 20000);
		return orderNo;
	}

	private SellerPaymentPageResponse page() {
		return page(null);
	}

	private SellerPaymentPageResponse page(SellerPaymentStatus status) {
		return sellerPaymentService.list(SELLER_KAKAO, status, null, null, 0, 20);
	}

	private SellerPaymentDetailResponse detail(String paymentNo) {
		return sellerPaymentService.detail(SELLER_KAKAO, paymentNo);
	}

	private List<String> paymentNos() {
		return page().items().stream()
				.map(SellerPaymentPageResponse.SellerPaymentItem::paymentNo).toList();
	}

	private List<String> search(String q) {
		return sellerPaymentService.list(SELLER_KAKAO, null, null, q, 0, 20).items().stream()
				.map(SellerPaymentPageResponse.SellerPaymentItem::paymentNo).toList();
	}

	private static long countOf(SellerPaymentPageResponse page, SellerPaymentStatus status) {
		SellerPaymentPageResponse.ChipCounts counts = page.counts();
		return switch (status) {
			case PAID -> counts.paid();
			case SETTLED -> counts.settled();
			case CANCELING -> counts.canceling();
			case CANCELED -> counts.canceled();
			case FAILED -> counts.failed();
			case PENDING -> counts.pending();
		};
	}

	private String orderStatus(String orderNo) {
		return jdbcTemplate.queryForObject("""
				SELECT o.status FROM orders o
				  JOIN order_group g ON g.id = o.order_group_id
				 WHERE g.order_no = ?
				""", String.class, orderNo);
	}

	private int sold(SaleForm form) {
		return jdbcTemplate.queryForObject(
				"SELECT sold FROM sale_form WHERE id = ?", Integer.class, form.getId());
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

	private void addOrder(OrderGroup group, SaleForm form, OrderStatus orderStatus) {
		Product product = form.getProducts().get(0);
		Order order = Order.create(form);
		order.addItem(OrderItem.snapshotOf(product, product.getOptions().get(0), 2));
		group.addOrder(order);
		group.applyShippingFee(3000);

		pendingStatuses.add(orderStatus);
	}

	/**
	 * 저장한 뒤 상태를 SQL 로 맞춘다.
	 *
	 * 엔티티가 전이를 강제해서(PAID 를 거치지 않으면 ARRIVED 가 안 된다) 자바로는
	 * 원하는 조합을 만들기 어렵다. 조회를 검증하는 테스트라 결과 상태만 있으면 된다.
	 * 재고는 팔린 것으로 맞춰 둔다 — 취소가 되돌릴 자리가 있어야 한다.
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
			Order order = orders.get(i);
			jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?",
					pendingStatuses.get(i).name(), order.getId());
			jdbcTemplate.update("UPDATE sale_form SET sold = sold + ? WHERE id = ?",
					order.getQty(), order.getSaleForm().getId());
		}
		pendingStatuses.clear();
		return group.getOrderNo();
	}

	private long payment(String orderNo, String phase, String status, int amount) {
		Long groupId = jdbcTemplate.queryForObject(
				"SELECT id FROM order_group WHERE order_no = ?", Long.class, orderNo);

		jdbcTemplate.update("""
						INSERT INTO payment (order_group_id, phase, session_id, amount,
						                     tax_free_amount, status, captured_at)
						VALUES (?, ?, ?, ?, 0, ?, ?)
						""",
				groupId, phase, "pymt_" + System.nanoTime(), amount, status,
				"CAPTURED".equals(status) ? LocalDateTime.now() : null);

		return jdbcTemplate.queryForObject(
				"SELECT id FROM payment WHERE order_group_id = ? AND phase = ?",
				Long.class, groupId, phase);
	}

	private void refund(long paymentId, String status, boolean settledManual,
	                    LocalDateTime manualRefundedAt, int amount) {
		jdbcTemplate.update("""
						INSERT INTO refund (payment_id, idempotency_key, amount, tax_free_amount,
						                    vat, requested_by, status, settled_manual, manual_refunded_at)
						VALUES (?, ?, ?, 0, 0, 'BUYER', ?, ?, ?)
						""",
				paymentId, "rf_" + System.nanoTime(), amount, status, settledManual ? 1 : 0,
				manualRefundedAt);
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
