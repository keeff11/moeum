package store.moeum.moeum.seller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.seller.dto.OnboardingRequest;
import store.moeum.moeum.support.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerServiceTest extends IntegrationTest {

	private static final String KAKAO_ID = "kakao-seller-test";

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		saleFormRepository.deleteAll();
		sellerRepository.deleteAll();
	}

	@Test
	@DisplayName("온보딩을_제출하면_PENDING으로_들어간다")
	void 온보딩을_제출하면_PENDING으로_들어간다() {
		Seller seller = sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		assertThat(seller.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
		assertThat(seller.getApprovedAt()).isNull();
		assertThat(seller.getShippingFee()).isEqualTo(3000);
		assertThat(seller.getFreeShippingOver()).isEqualTo(50000);
	}

	@Test
	@DisplayName("온보딩의_민감정보는_DB에_암호문으로_들어간다")
	void 온보딩의_민감정보는_DB에_암호문으로_들어간다() {
		Seller seller = sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		byte[] businessNo = jdbcTemplate.queryForObject(
				"SELECT business_no_enc FROM seller WHERE id = ?", byte[].class, seller.getId());
		byte[] account = jdbcTemplate.queryForObject(
				"SELECT settlement_acct_enc FROM seller WHERE id = ?", byte[].class, seller.getId());

		assertThat(new String(businessNo)).doesNotContain("1234567890");
		assertThat(new String(account)).doesNotContain("123456-78-901234");
	}

	@Test
	@DisplayName("같은_카카오_계정으로_두_번_제출하면_거부한다")
	void 같은_카카오_계정으로_두_번_제출하면_거부한다() {
		sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		assertThatThrownBy(() -> sellerService.submitOnboarding(KAKAO_ID, request("another-store")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_ALREADY_REGISTERED);
	}

	@Test
	@DisplayName("이미_쓰는_판매공간_주소면_거부한다")
	void 이미_쓰는_판매공간_주소면_거부한다() {
		sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		assertThatThrownBy(() -> sellerService.submitOnboarding("kakao-other", request("moeum-store")))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.DUPLICATE_STORE_SLUG);
	}

	@Test
	@DisplayName("승인하면_APPROVED와_승인시각이_남는다")
	void 승인하면_APPROVED와_승인시각이_남는다() {
		Seller seller = sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		Seller approved = sellerService.approve(seller.getId());

		assertThat(approved.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
		assertThat(approved.getApprovedAt()).isNotNull();
		assertThat(approved.isApproved()).isTrue();
	}

	@Test
	@DisplayName("반려하면_REJECTED가_되고_승인시각이_지워진다")
	void 반려하면_REJECTED가_되고_승인시각이_지워진다() {
		Seller seller = sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));
		sellerService.approve(seller.getId());

		Seller rejected = sellerService.reject(seller.getId());

		assertThat(rejected.getReviewStatus()).isEqualTo(ReviewStatus.REJECTED);
		assertThat(rejected.getApprovedAt()).isNull();
	}

	@Test
	@DisplayName("없는_셀러를_승인하면_404다")
	void 없는_셀러를_승인하면_404다() {
		assertThatThrownBy(() -> sellerService.approve(99999L))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_NOT_FOUND);
	}

	@Test
	@DisplayName("무료배송_기준을_넘으면_배송비가_0이다")
	void 무료배송_기준을_넘으면_배송비가_0이다() {
		Seller seller = sellerService.submitOnboarding(KAKAO_ID, request("moeum-store"));

		assertThat(seller.shippingFeeFor(49999)).isEqualTo(3000);
		assertThat(seller.shippingFeeFor(50000)).isZero();
	}

	private OnboardingRequest request(String storeSlug) {
		return new OnboardingRequest(
				storeSlug, "모으미 상점", "1234567890", "국민 123456-78-901234",
				"홍길동", "010-1234-5678", "seller@example.com",
				3000, 50000
		);
	}
}
