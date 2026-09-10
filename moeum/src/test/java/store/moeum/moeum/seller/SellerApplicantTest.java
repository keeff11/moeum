package store.moeum.moeum.seller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.seller.dto.OnboardingRequest;
import store.moeum.moeum.seller.dto.SellerApplicantPageResponse;
import store.moeum.moeum.support.IntegrationTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 셀러 심사 신청자 목록 · 수락 (운영자, D-039).
 *
 * 이 테스트가 지키려는 것:
 * <ul>
 *   <li><b>먼저 낸 신청이 먼저 처리된다</b> — 최신순이면 오래된 신청이 바닥에 깔린다</li>
 *   <li>사업자번호는 심사 목록에만 나온다. 암호화 컬럼이 제대로 복호화돼 실린다</li>
 *   <li><b>정산계좌는 어디에도 안 나온다</b> — 심사에 필요한 값이 아니다</li>
 *   <li>수락하면 그 신청은 대기 목록에서 빠진다</li>
 * </ul>
 */
class SellerApplicantTest extends IntegrationTest {

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private WebApplicationContext context;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		saleFormRepository.deleteAll();
		sellerRepository.deleteAll();
		mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	// ---------------------------------------------------------------- 목록

	@Test
	@DisplayName("기본은_대기_중인_신청만_준다")
	void 기본은_대기_중() {
		Seller pending = apply("kakao-a", "store-a");
		Seller approved = apply("kakao-b", "store-b");
		sellerService.approve(approved.getId());

		SellerApplicantPageResponse response = applicants(ReviewStatus.PENDING);

		// 이 화면을 여는 이유가 "처리할 신청이 있나" 다
		assertThat(response.items()).extracting(SellerApplicantPageResponse.Applicant::id)
				.containsExactly(pending.getId());
	}

	@Test
	@DisplayName("먼저_낸_신청이_위에_온다")
	void 대기열_순서() {
		Seller first = apply("kakao-first", "store-first");
		Seller second = apply("kakao-second", "store-second");
		Seller third = apply("kakao-third", "store-third");

		// 같은 초에 저장되면 순서를 못 가리므로 신청 시각을 벌려 둔다
		applyAt(first, LocalDateTime.now().minusDays(3));
		applyAt(second, LocalDateTime.now().minusDays(2));
		applyAt(third, LocalDateTime.now().minusDays(1));

		// 심사는 대기열이다. 최신순이면 늦게 낸 신청만 처리되고 오래된 것이 바닥에 깔린다
		assertThat(applicants(ReviewStatus.PENDING).items())
				.extracting(SellerApplicantPageResponse.Applicant::id)
				.containsExactly(first.getId(), second.getId(), third.getId());
	}

	@Test
	@DisplayName("사업자번호가_복호화돼_실린다")
	void 사업자번호() {
		apply("kakao-biz", "store-biz");

		SellerApplicantPageResponse.Applicant applicant = applicants(ReviewStatus.PENDING).items().get(0);

		// 이 값이 없으면 승인 여부를 판단할 수 없다 — 심사가 곧 사업자 확인이다
		assertThat(applicant.businessNo()).isEqualTo("1234567890");
		assertThat(applicant.representativeName()).isEqualTo("홍길동");
		assertThat(applicant.email()).isEqualTo("seller@example.com");
	}

	@Test
	@DisplayName("정산계좌는_심사_목록에도_담기지_않는다")
	void 정산계좌는_담지_않는다() throws Exception {
		apply("kakao-acct", "store-acct");

		// 심사가 아니라 정산에 쓰는 값이다. 필요한 것만 꺼낸다
		mockMvc.perform(get("/admin/sellers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].settlementAccount").doesNotExist())
				.andExpect(jsonPath("$.items[0].businessNo").value("1234567890"));
	}

	@Test
	@DisplayName("상태로_걸러_반려된_신청도_되짚을_수_있다")
	void 상태_필터() {
		Seller rejected = apply("kakao-rej", "store-rej");
		sellerService.reject(rejected.getId());

		assertThat(applicants(ReviewStatus.PENDING).items()).isEmpty();
		assertThat(applicants(ReviewStatus.REJECTED).items())
				.extracting(SellerApplicantPageResponse.Applicant::id)
				.containsExactly(rejected.getId());
	}

	@Test
	@DisplayName("신청이_없으면_빈_목록이고_오류가_아니다")
	void 빈_목록() {
		SellerApplicantPageResponse response = applicants(ReviewStatus.PENDING);

		assertThat(response.items()).isEmpty();
		assertThat(response.page().totalElements()).isZero();
		assertThat(response.page().hasNext()).isFalse();
	}

	@Test
	@DisplayName("페이지_크기는_100을_넘지_않는다")
	void 페이지_크기_상한() {
		apply("kakao-size", "store-size");

		assertThat(sellerService.applicants(ReviewStatus.PENDING, 0, 500).page().size()).isEqualTo(100);
		assertThat(sellerService.applicants(ReviewStatus.PENDING, 0, 0).page().size()).isEqualTo(20);
	}

	// ---------------------------------------------------------------- 수락

	@Test
	@DisplayName("수락하면_대기_목록에서_빠지고_승인_시각이_남는다")
	void 수락() throws Exception {
		Seller seller = apply("kakao-ok", "store-ok");

		mockMvc.perform(post("/admin/sellers/{sellerId}/approve", seller.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
				.andExpect(jsonPath("$.approvedAt").exists());

		assertThat(applicants(ReviewStatus.PENDING).items()).isEmpty();
		assertThat(applicants(ReviewStatus.APPROVED).items())
				.extracting(SellerApplicantPageResponse.Applicant::id)
				.containsExactly(seller.getId());
	}

	@Test
	@DisplayName("없는_셀러를_수락하면_404_다")
	void 없는_셀러() throws Exception {
		mockMvc.perform(post("/admin/sellers/{sellerId}/approve", 999999L))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("목록은_캐시하지_않는다")
	void 캐시_금지() throws Exception {
		apply("kakao-cache", "store-cache");

		// 방금 승인한 건이 목록에 남아 있으면 두 번 승인하게 된다
		mockMvc.perform(get("/admin/sellers"))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	// ---------------------------------------------------------------- 도우미

	private SellerApplicantPageResponse applicants(ReviewStatus status) {
		return sellerService.applicants(status, 0, 20);
	}

	private Seller apply(String kakaoId, String storeSlug) {
		return sellerService.submitOnboarding(kakaoId, new OnboardingRequest(
				storeSlug, "모으미 상점", "1234567890", "국민 123456-78-901234",
				"홍길동", "010-1234-5678", "seller@example.com",
				3000, 50000));
	}

	/** created_at 은 감사 필드라 자바로는 못 바꾼다 */
	private void applyAt(Seller seller, LocalDateTime at) {
		jdbcTemplate.update("UPDATE seller SET created_at = ? WHERE id = ?", at, seller.getId());
	}
}
