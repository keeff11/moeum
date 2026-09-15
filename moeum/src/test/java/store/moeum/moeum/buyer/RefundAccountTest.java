package store.moeum.moeum.buyer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.buyer.domain.BuyerRefundAccountRepository;
import store.moeum.moeum.buyer.dto.RefundAccountRequest;
import store.moeum.moeum.buyer.dto.RefundAccountResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;
import store.moeum.moeum.payment.PaymentService;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 환불 계좌 (D-057).
 *
 * 확인하려는 것은 셋이다.
 * <ul>
 *   <li><b>1차금 결제의 선행 조건이다</b> — 없으면 세션을 만들기 전에 막힌다</li>
 *   <li><b>판매 유형을 가리지 않는다</b> — 단독(재고) 판매도 예외가 아니다</li>
 *   <li><b>계좌번호가 평문으로 남지 않는다</b> — DB 에도, 응답에도</li>
 * </ul>
 */
class RefundAccountTest extends IntegrationTest {

	@Autowired
	private RefundAccountService refundAccountService;

	@Autowired
	private BuyerRefundAccountRepository refundAccountRepository;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	// ---------------------------------------------------------------- 등록 · 조회

	@Test
	@DisplayName("등록하면_가려진_번호로_조회된다")
	void 등록() {
		RefundAccountResponse saved = refundAccountService.save(buyer(), request("1002-123-456789"));

		assertThat(saved.bank()).isEqualTo("국민은행");
		assertThat(saved.holderName()).isEqualTo("김서연");
		// 본인 계좌라도 전체 번호를 다시 내려보내지 않는다. 응답 본문에 남으면 캐시에도 남는다
		assertThat(saved.accountNoMasked()).isEqualTo("****6789");

		assertThat(refundAccountService.find(buyer())).get()
				.extracting(RefundAccountResponse::accountNoMasked).isEqualTo("****6789");
	}

	@Test
	@DisplayName("등록_전에는_빈_결과다")
	void 등록_전() {
		// 첫 방문자에게 404 를 주면 결제 화면이 오류로 시작한다. 프론트는 빈 폼을 그린다
		assertThat(refundAccountService.find(buyer())).isEmpty();
	}

	@Test
	@DisplayName("다시_등록하면_덮어쓴다")
	void 교체() {
		refundAccountService.save(buyer(), request("1002-123-456789"));
		RefundAccountResponse changed = refundAccountService.save(buyer(),
				new RefundAccountRequest("신한은행", "110-222-333444", "김서연"));

		assertThat(changed.bank()).isEqualTo("신한은행");
		assertThat(changed.accountNoMasked()).isEqualTo("****3444");
		// 계좌부가 아니다. 한 행만 남아야 두 계좌 중 어디로 보낼지 헷갈릴 일이 없다
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	@DisplayName("하이픈은_떼고_저장한다")
	void 정규화() {
		refundAccountService.save(buyer(), request("1002-123-456789"));

		// 암호화 컬럼이라 나중에 정규화할 방법이 없다 — 들어올 때 맞춰 둬야 한다.
		// 암호문은 IV 가 매번 달라 비교할 수 없으니 복호화된 값으로 본다
		Long buyerId = jdbcTemplate.queryForObject(
				"SELECT buyer_id FROM buyer_refund_account", Long.class);
		assertThat(refundAccountRepository.findByBuyerId(buyerId).orElseThrow().getAccountNo())
				.isEqualTo("1002123456789");
	}

	@Test
	@DisplayName("계좌번호는_DB에_평문으로_남지_않는다")
	void 암호화() {
		refundAccountService.save(buyer(), request("1002-123-456789"));

		String hex = jdbcTemplate.queryForObject(
				"SELECT HEX(account_no_enc) FROM buyer_refund_account", String.class);
		String plainHex = toHex("1002123456789");

		assertThat(hex).doesNotContain(plainHex);
	}

	// ---------------------------------------------------------------- 결제의 선행 조건

	@Test
	@DisplayName("환불계좌가_없으면_1차금_결제를_시작할_수_없다")
	void 공동구매_차단() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		String sessionToken = placeWithoutRefundAccount(setup);

		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_ACCOUNT_REQUIRED);
	}

	@Test
	@DisplayName("단독_재고판매도_예외가_아니다")
	void 단독_차단() {
		// 정산 후 환불은 판매 유형과 무관하게 생긴다. 단독만 빼 두면 그때 보낼 곳이 없다
		OrderFixture.Setup setup = fixture.soloSaleForm(10);
		String sessionToken = placeWithoutRefundAccount(setup);

		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.REFUND_ACCOUNT_REQUIRED);
	}

	@Test
	@DisplayName("막혀도_홀드는_살아_있다")
	void 홀드_유지() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		String sessionToken = placeWithoutRefundAccount(setup);

		assertThatThrownBy(() -> paymentService.pay(buyer(), sessionToken))
				.isInstanceOf(BusinessException.class);

		// 계좌를 입력하고 그대로 이어서 결제해야 한다. 여기서 재고를 풀면 30분 타이머의 뜻이 없다
		assertThat(jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, setup.saleFormId()))
				.isEqualTo(2);
	}

	// ---------------------------------------------------------------- 도구

	/** 배송지까지만 갖춘 구매자로 주문을 만든다 — 환불 계좌만 없는 상태 */
	private String placeWithoutRefundAccount(OrderFixture.Setup setup) {
		fixture.buyerWithAddress("kakao-refund-acct", "김서연");
		jdbcTemplate.update("DELETE FROM buyer_refund_account");

		OrderGroupResponse group = orderService.place(buyer(),
				new OrderCreateRequest(List.of(new OrderCreateRequest.Item(setup.optionId(), 2))));
		return group.sessionToken();
	}

	private static RefundAccountRequest request(String accountNo) {
		return new RefundAccountRequest("국민은행", accountNo, "김서연");
	}

	private static String toHex(String plain) {
		StringBuilder hex = new StringBuilder();
		for (byte b : plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
			hex.append(String.format("%02X", b));
		}
		return hex.toString();
	}

	private SessionUser buyer() {
		return new SessionUser("kakao-refund-acct", "김서연");
	}

	private Integer rows() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM buyer_refund_account", Integer.class);
	}
}
