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
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.ShipmentRequest;
import store.moeum.moeum.order.dto.TrackingResponse;
import store.moeum.moeum.order.infra.SmartTrackerClient;
import store.moeum.moeum.order.infra.TrackingCache;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배송조회 (D-048).
 *
 * <b>여기서 지키는 것은 하나다 — 조회가 안 되더라도 송장번호는 잃지 않는다.</b>
 * 택배사가 아직 송장을 인식하지 못했거나 그쪽이 죽은 것일 수 있는데, 그때 404 를 주면
 * 구매자가 택배사 사이트에서 직접 조회할 길까지 막힌다.
 *
 * <b>200 이 성공이 아니다.</b> 스마트택배는 조회 실패도 200 에 {@code status:false} 로
 * 준다 — 상태 코드만 보면 빈 배송 정보를 정상 조회로 친다. SOLAPI 와 같은 함정이다 (D-040).
 */
class TrackingTest extends IntegrationTest {

	private static final String SESSION_ID = "pymt_sess-019f0000-0000-7000-9000-0000000000f1";
	private static final WireMockServer POINT3 = new WireMockServer(wireMockConfig().dynamicPort());
	private static final WireMockServer TRACKER = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		POINT3.start();
		TRACKER.start();
	}

	@DynamicPropertySource
	static void external(DynamicPropertyRegistry registry) {
		registry.add("moeum.point3.base-url", POINT3::baseUrl);
		registry.add("moeum.point3.api-token", () -> "test-token");
		registry.add("moeum.tracking.base-url", TRACKER::baseUrl);
		registry.add("moeum.tracking.api-key", () -> "test-tracker-key");
	}

	@Autowired
	private TrackingService trackingService;

	@Autowired
	private ShipmentService shipmentService;

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

	/** 캐시가 싱글턴이라 비우지 않으면 앞 테스트가 담아 둔 값이 넘어온다 */
	@Autowired
	private TrackingCache cache;

	private OrderFixture.Setup setup;
	private String sellerKakaoId;
	private String orderNo;
	private String orderToken;

	@BeforeEach
	void setUp() {
		POINT3.resetAll();
		TRACKER.resetAll();
		cache.clear();
		fixture.clean();
		fixture.buyerWithAddress("kakao-payer", "김서연");
		setup = fixture.soloSaleForm(10);
		sellerKakaoId = sellerRepository.findById(setup.sellerId()).orElseThrow().getKakaoId();
	}

	@AfterEach
	void tearDown() {
		POINT3.resetAll();
		TRACKER.resetAll();
	}

	// ---------------------------------------------------------------- 조회

	@Test
	@DisplayName("배송_단계를_오래된_것부터_준다")
	void 조회_성공() {
		shipWith("04");
		stubTracking("""
				{"invoiceNo":"123456789012","level":6,"completeYN":"Y",
				 "trackingDetails":[
				   {"timeString":"2026-09-11 09:00:00","where":"서울강남","kind":"집화처리","level":2},
				   {"timeString":"2026-09-12 14:03:00","where":"서울강남","kind":"배송완료","level":6}]}""");

		TrackingResponse response = trackingService.track("kakao-payer", orderToken);

		assertThat(response.available()).isTrue();
		assertThat(response.carrier()).isEqualTo("CJ대한통운");
		assertThat(response.trackingNo()).isEqualTo("123456789012");
		assertThat(response.level()).isEqualTo(6);
		assertThat(response.completed()).isTrue();
		assertThat(response.steps()).extracting(TrackingResponse.Step::kind)
				.containsExactly("집화처리", "배송완료");
	}

	@Test
	@DisplayName("200_인데_status_false_면_조회_실패로_본다")
	void status_false() {
		// 상태 코드만 보면 빈 배송 정보를 정상 조회로 치고 화면이 빈 채로 뜬다
		shipWith("04");
		stubTracking("""
				{"status":false,"msg":"운송장 번호가 올바르지 않습니다.","code":"104"}""");

		TrackingResponse response = trackingService.track("kakao-payer", orderToken);

		assertThat(response.available()).isFalse();
		assertThat(response.message()).contains("운송장");
		assertThat(response.steps()).isEmpty();
	}

	@Test
	@DisplayName("조회에_실패해도_송장번호는_그대로_준다")
	void 실패해도_송장번호는_남는다() {
		// 여기서 404 를 주면 구매자가 택배사 사이트에서 직접 조회할 길까지 막힌다
		shipWith("04");
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/trackingInfo"))
				.willReturn(aResponse().withStatus(500)));

		TrackingResponse response = trackingService.track("kakao-payer", orderToken);

		assertThat(response.available()).isFalse();
		assertThat(response.carrier()).isEqualTo("CJ대한통운");
		assertThat(response.trackingNo()).isEqualTo("123456789012");
	}

	@Test
	@DisplayName("택배사_코드_없이_등록된_주문은_조회를_제공하지_않는다")
	void 코드_없음() {
		// 조회 키가 없던 때 등록된 건이다. 억지로 조회하면 엉뚱한 배송이 나온다
		shipWith(null);

		TrackingResponse response = trackingService.track("kakao-payer", orderToken);

		assertThat(response.available()).isFalse();
		assertThat(response.trackingNo()).isEqualTo("123456789012");
		TRACKER.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/trackingInfo")));
	}

	@Test
	@DisplayName("남의_주문은_조회할_수_없다")
	void 남의_주문() {
		// 송장번호는 수령인 정보에 가깝다. 소유권을 쿼리에 박아서 지킨다
		shipWith("04");

		assertThatThrownBy(() -> trackingService.track("kakao-stranger", orderToken))
				.isInstanceOf(BusinessException.class);
	}

	// ---------------------------------------------------------------- 택배사 목록

	@Test
	@DisplayName("택배사_목록을_받아_온다")
	void 택배사_목록() {
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/companylist"))
				.willReturn(json("""
						{"Company":[{"Code":"04","Name":"CJ대한통운","International":"false"},
						            {"Code":"05","Name":"한진택배","International":"false"}]}""")));

		List<SmartTrackerClient.Carrier> carriers = trackingService.carriers();

		assertThat(carriers).extracting(SmartTrackerClient.Carrier::code)
				.containsExactly("04", "05");
		assertThat(carriers).extracting(SmartTrackerClient.Carrier::name)
				.containsExactly("CJ대한통운", "한진택배");
	}

	@Test
	@DisplayName("목록을_감싸지_않고_배열로_와도_읽는다")
	void 배열_응답() {
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/companylist"))
				.willReturn(json("""
						[{"Code":"04","Name":"CJ대한통운"}]""")));

		assertThat(trackingService.carriers()).hasSize(1);
	}

	@Test
	@DisplayName("목록_조회가_실패하면_빈_목록이다")
	void 목록_실패() {
		// 여기서 예외를 던지면 송장 등록 화면 자체가 안 열린다 — 등록을 막으면 안 된다
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/companylist"))
				.willReturn(aResponse().withStatus(503)));

		assertThat(trackingService.carriers()).isEmpty();
	}

	// ---------------------------------------------------------------- 캐시

	@Test
	@DisplayName("같은_송장을_또_봐도_택배사에_다시_묻지_않는다")
	void 캐시가_막는다() {
		// 이용권이 월 100건이다. 구매자가 새로고침할 때마다 한 건씩 쓰면 금방 소진된다
		shipWith("04");
		stubTracking("""
				{"invoiceNo":"123456789012","level":3,"completeYN":"N",
				 "trackingDetails":[{"timeString":"2026-09-11 09:00:00","where":"서울강남","kind":"집화처리","level":2}]}""");

		trackingService.track("kakao-payer", orderToken);
		trackingService.track("kakao-payer", orderToken);
		trackingService.track("kakao-payer", orderToken);

		TRACKER.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/trackingInfo")));
	}

	@Test
	@DisplayName("조회에_실패해도_바로_다시_묻지_않는다")
	void 실패도_캐시한다() {
		// 그쪽이 죽어 있으면 새로고침마다 이용권을 한 건씩 태운다
		shipWith("04");
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/trackingInfo"))
				.willReturn(aResponse().withStatus(500)));

		trackingService.track("kakao-payer", orderToken);
		TrackingResponse second = trackingService.track("kakao-payer", orderToken);

		TRACKER.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/trackingInfo")));
		assertThat(second.available()).isFalse();
		assertThat(second.trackingNo()).isEqualTo("123456789012");
	}

	@Test
	@DisplayName("택배사_목록도_한_번만_받아_온다")
	void 목록도_캐시한다() {
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/companylist"))
				.willReturn(json("""
						{"Company":[{"Code":"04","Name":"CJ대한통운"}]}""")));

		trackingService.carriers();
		trackingService.carriers();

		TRACKER.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/companylist")));
	}

	// ---------------------------------------------------------------- 도우미

	/** 결제 → 입고 → 송장 등록까지 한 번에 */
	private void shipWith(String carrierCode) {
		String sessionToken = orderService.place(buyer(), order()).sessionToken();
		POINT3.stubFor(post(urlPathEqualTo("/payment/v3/session"))
				.willReturn(json("""
						{"id":"%s","status":"created","amount":35000,
						 "supplyAmount":31819,"vat":3181,"taxFreeAmount":0,"currency":"KRW"}
						""".formatted(SESSION_ID))));
		String token = paymentService.pay(buyer(), sessionToken).orderToken();
		POINT3.stubFor(post(urlPathEqualTo("/capture/v2/" + SESSION_ID))
				.willReturn(json("""
						{"id":"%s","status":"captured"}""".formatted(SESSION_ID))));
		paymentService.confirm(buyer(), token, SESSION_ID, null);

		jdbcTemplate.update("UPDATE orders SET status = 'ARRIVED'");
		orderNo = jdbcTemplate.queryForObject("SELECT order_no FROM order_group", String.class);
		orderToken = token;

		shipmentService.register(sellerKakaoId, orderNo,
				new ShipmentRequest("CJ대한통운", carrierCode, "123456789012"));
	}

	private void stubTracking(String body) {
		TRACKER.stubFor(get(urlPathEqualTo("/api/v1/trackingInfo")).willReturn(json(body)));
	}

	private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody(body);
	}

	private static SessionUser buyer() {
		return new SessionUser("kakao-payer", "결제자");
	}

	private OrderCreateRequest order() {
		return new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 1)));
	}
}
