package store.moeum.moeum.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.payment.dto.PaymentResultResponse;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발주서 (D-045).
 *
 * <b>이 파일의 숫자가 곧 셀러가 공장에 주문하는 수량이다.</b> 틀리면 셀러가 돈을 쓴다.
 * 그래서 "무엇을 세고 무엇을 안 세는가" 의 경계를 전부 못 박는다.
 */
class PurchaseOrderTest extends IntegrationTest {

	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());

	/** payment.session_id 가 유니크라 결제마다 다른 값을 써야 한다 */
	private int sessionSeq;

	static {
		POINT3.start();
	}

	@DynamicPropertySource
	static void point3(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
	}

	@Autowired
	private PurchaseOrderService purchaseOrderService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	private OrderFixture.Setup setup;
	private String sellerKakaoId;
	private String sessionId;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		sessionSeq = 0;
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.saleForm(100, null);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
	}

	// ---------------------------------------------------------------- 무엇을 세는가

	@Test
	@DisplayName("결제가_끝난_주문만_센다")
	void 결제된_것만() {
		payFor(setup.optionId(), 3);
		// 결제까지 가지 않은 주문. 돈이 안 들어왔으니 발주 대상이 아니다
		orderService.place(other(), order(setup.optionId(), 7));

		assertThat(rows()).containsExactly("옵션 A,3,0,3");
	}

	@Test
	@DisplayName("승인_결과를_기다리는_주문은_세지_않는다")
	void 결과_불명은_뺀다() {
		// 출금됐는지 모르는 건을 넣으면 돈이 안 들어온 수량까지 공장에 주문하게 된다
		String orderToken = startPayment(setup.optionId(), 5);
		stubCapture(500, "{}");
		paymentService.confirm(buyer(), orderToken, sessionId, null);

		assertThat(paymentStatus()).containsExactly("CAPTURE_PENDING");
		assertThat(rows()).isEmpty();
	}

	@Test
	@DisplayName("발주_뒤_상태가_올라가도_계속_나온다")
	void 발주_후에도_받을_수_있다() {
		// PAID 로만 거르면 PRODUCING 으로 올린 뒤 다시 받는 발주서가 빈 파일이 된다
		payFor(setup.optionId(), 4);
		jdbcTemplate.update("UPDATE orders SET status = 'PRODUCING'");

		assertThat(rows()).containsExactly("옵션 A,4,0,4");
	}

	@Test
	@DisplayName("취소된_수량은_따로_세고_발주_수량에서_뺀다")
	void 취소분() {
		payFor(setup.optionId(), 6);
		payForAsOther(setup.optionId(), 2);
		jdbcTemplate.update("UPDATE orders SET status = 'CANCELED' WHERE qty = 2");

		// 셀러가 "원래 8개였고 2개가 취소됐다" 를 대조할 수 있어야 한다
		assertThat(rows()).containsExactly("옵션 A,8,2,6");
	}

	@Test
	@DisplayName("옵션별로_한_줄씩_폼에서_정한_순서대로_나온다")
	void 옵션별_집계() {
		payForBoth(2, 5);

		assertThat(rows()).containsExactly("옵션 A,2,0,2", "옵션 B,5,0,5");
	}

	@Test
	@DisplayName("옵션_이름을_바꿔도_한_줄이고_바뀐_이름으로_나온다")
	void 이름_변경() {
		// 공장이 받아야 하는 것은 지금 이름 하나다. 스냅샷으로 묶으면 옛 이름과 새 이름 두 줄이 된다
		payFor(setup.optionId(), 3);
		jdbcTemplate.update("UPDATE product_option SET name = '네이비' WHERE id = ?", setup.optionId());
		payForAsOther(setup.optionId(), 2);

		assertThat(rows()).containsExactly("네이비,5,0,5");
	}

	@Test
	@DisplayName("주문이_없으면_머리글만_나온다")
	void 빈_발주서() {
		assertThat(rows()).isEmpty();
		assertThat(csv()).startsWith("﻿상품명,옵션명,주문 수량,취소 수량,발주 수량\r\n");
	}

	// ---------------------------------------------------------------- 잠정 표시

	@Test
	@DisplayName("마감_전이면_파일_이름에_잠정이_붙는다")
	void 마감_전은_잠정() {
		payFor(setup.optionId(), 3);

		PurchaseOrderService.PurchaseOrderFile file =
				purchaseOrderService.create(sellerKakaoId, setup.saleFormId());

		assertThat(file.provisional()).isTrue();
		assertThat(file.fileName()).contains("_잠정.csv");
	}

	@Test
	@DisplayName("마감됐어도_미달_처리_전이면_잠정이다")
	void 미달_처리_전은_잠정() {
		// 곧 자동취소될 주문이 발주서에 들어 있다 (D-026)
		payFor(setup.optionId(), 3);
		jdbcTemplate.update("UPDATE sale_form SET status = 'CLOSED', shortfall_done_at = NULL");

		assertThat(purchaseOrderService.create(sellerKakaoId, setup.saleFormId()).provisional()).isTrue();
	}

	@Test
	@DisplayName("마감되고_미달_처리까지_끝나면_확정이다")
	void 확정() {
		payFor(setup.optionId(), 3);
		jdbcTemplate.update("UPDATE sale_form SET status = 'CLOSED', shortfall_done_at = NOW(6)");

		PurchaseOrderService.PurchaseOrderFile file =
				purchaseOrderService.create(sellerKakaoId, setup.saleFormId());

		assertThat(file.provisional()).isFalse();
		assertThat(file.fileName()).doesNotContain("잠정").endsWith(".csv");
	}

	// ---------------------------------------------------------------- 소유권

	@Test
	@DisplayName("남의_판매는_404_다")
	void 남의_판매() {
		Seller stranger = sellerRepository.save(Seller.builder()
				.kakaoId("kakao-stranger-seller").storeSlug("stranger-store").shippingFee(3000).build());
		stranger.approve();
		sellerRepository.saveAndFlush(stranger);

		// 403 이면 "그 id 에 폼이 있긴 하다" 가 새어 나간다
		assertThatThrownBy(() -> purchaseOrderService.create(stranger.getKakaoId(), setup.saleFormId()))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SALE_FORM_NOT_FOUND);
	}

	// ---------------------------------------------------------------- 도우미

	/** 머리글을 뺀 본문. 상품명은 전부 같아서 떼고 본다 */
	private List<String> rows() {
		return Arrays.stream(csv().split("\r\n")).skip(1)
				.map(line -> line.substring(line.indexOf(',') + 1))
				.toList();
	}

	private String csv() {
		return new String(purchaseOrderService.create(sellerKakaoId, setup.saleFormId()).content(),
				StandardCharsets.UTF_8);
	}

	private void payFor(Long optionId, int qty) {
		pay(buyer(), order(optionId, qty));
	}

	private void payForAsOther(Long optionId, int qty) {
		fixture.buyerWithAddress("kakao-other", "이하늘");
		pay(other(), order(optionId, qty));
	}

	private void payForBoth(int qtyA, int qtyB) {
		pay(buyer(), new OrderCreateRequest(List.of(
				new OrderCreateRequest.Item(setup.optionId(), qtyA),
				new OrderCreateRequest.Item(setup.secondOptionId(), qtyB))));
	}

	private void pay(SessionUser user, OrderCreateRequest request) {
		String sessionToken = orderService.place(user, request).sessionToken();
		nextSession();
		String orderToken = paymentService.pay(user, sessionToken).orderToken();
		stubCapture(200, """
				{"id":"%s","status":"captured"}""".formatted(sessionId));

		PaymentResultResponse result = paymentService.confirm(user, orderToken, sessionId, null);
		assertThat(result.status()).isEqualTo(PaymentResultResponse.Status.PAID);
	}

	private String startPayment(Long optionId, int qty) {
		String sessionToken = orderService.place(buyer(), order(optionId, qty)).sessionToken();
		nextSession();
		return paymentService.pay(buyer(), sessionToken).orderToken();
	}

	/** 다음 결제에 쓸 세션 id 를 정하고 세션 생성 스텁을 그 값으로 바꾼다 */
	private void nextSession() {
		sessionId = "pymt_sess-019f0000-0000-7000-9000-%012d".formatted(++sessionSeq);
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json(200, """
						{"id":"%s","status":"created","amount":99000,
						 "supplyAmount":90000,"vat":9000,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(sessionId))));
	}

	private void stubCapture(int status, String body) {
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + sessionId))
				.willReturn(json(status, body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
		return aResponse().withStatus(status)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private static SessionUser other() {
		return new SessionUser("kakao-other", "다른 구매자");
	}

	private OrderCreateRequest order(Long optionId, int qty) {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(optionId, qty)));
	}

	private List<String> paymentStatus() {
		return jdbcTemplate.queryForList("SELECT status FROM payment", String.class);
	}
}
