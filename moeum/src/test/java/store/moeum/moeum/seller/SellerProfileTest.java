package store.moeum.moeum.seller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.seller.dto.SellerProfileRequest;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 셀러 설정 화면 (와이어프레임 G12).
 *
 * 이 화면이 위험한 이유는 두 가지다 —
 * <b>판매공간 주소를 바꾸면 이미 뿌린 링크가 죽고</b>,
 * <b>문의 연락처를 잘못 만들면 대표자 개인 번호가 공개된다.</b>
 */
class SellerProfileTest extends IntegrationTest {

	private static final String KAKAO = "kakao-profile-seller";
	private static final String PHONE = "010-1234-5678";

	@Autowired
	private SellerService sellerService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		// FK 순서를 이미 아는 곳이 있다. 여기서 다시 세면 순서가 어긋난다
		fixture.clean();

		sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId(KAKAO)
				.storeSlug("before-slug")
				.storeName("이전 상점")
				.shippingFee(3000)
				.representativeName("홍길동")
				.phone(PHONE)
				.email("seller@moeum.store")
				.businessNo("1234567890")
				.settlementAccount("우리 1002-000-000000")
				.build());
	}

	// ---------------------------------------------------------------- 문의 연락처

	@Test
	@DisplayName("구매자_문의_연락처는_심사용_연락처와_별개로_저장된다")
	void 문의_연락처() {
		sellerService.updateProfile(KAKAO, request("before-slug", "010-9999-0000"));

		Seller seller = sellerRepository.findByKakaoId(KAKAO).orElseThrow();
		assertThat(seller.getPublicContact()).isEqualTo("010-9999-0000");
		// 심사·정산 담당자가 연락하는 번호는 그대로 있어야 한다
		assertThat(seller.getPhone()).isEqualTo(PHONE);
	}

	@Test
	@DisplayName("문의_연락처를_적지_않으면_심사용_연락처가_대신_나가지_않는다")
	void 연락처_미입력() {
		sellerService.updateProfile(KAKAO, request("before-slug", null));

		// 비어 있다고 phone 으로 채우면 대표자 개인 번호가 공개된다
		assertThat(sellerRepository.findByKakaoId(KAKAO).orElseThrow().getPublicContact()).isNull();
	}

	@Test
	@DisplayName("전화번호가_아니어도_받는다")
	void 자유_입력() {
		// 오픈채팅 링크나 이메일을 적는 셀러가 있다. 화면도 자유 입력이다
		sellerService.updateProfile(KAKAO, request("before-slug", "https://open.kakao.com/o/abcd"));

		assertThat(sellerRepository.findByKakaoId(KAKAO).orElseThrow().getPublicContact())
				.isEqualTo("https://open.kakao.com/o/abcd");
	}

	// ---------------------------------------------------------------- 판매공간 주소

	@Test
	@DisplayName("판매공간_주소를_바꿀_수_있다")
	void 주소_변경() {
		sellerService.updateProfile(KAKAO, request("after-slug", null));

		assertThat(sellerRepository.findByKakaoId(KAKAO).orElseThrow().getStoreSlug())
				.isEqualTo("after-slug");
	}

	@Test
	@DisplayName("바꾸면_이전_주소로는_찾을_수_없다")
	void 옛_주소는_죽는다() {
		sellerService.updateProfile(KAKAO, request("after-slug", null));

		// 이미 뿌린 링크가 죽는다는 뜻이다. 기능이 요구돼 막지 않았을 뿐 되돌릴 수 없다
		assertThat(sellerRepository.findByStoreSlug("before-slug")).isEmpty();
		assertThat(sellerRepository.findByStoreSlug("after-slug")).isPresent();
	}

	@Test
	@DisplayName("남이_쓰는_주소로는_바꿀_수_없다")
	void 중복_주소() {
		sellerRepository.saveAndFlush(Seller.builder()
				.kakaoId("kakao-other").storeSlug("taken-slug").storeName("남의 상점")
				.shippingFee(3000).build());

		assertThatThrownBy(() -> sellerService.updateProfile(KAKAO, request("taken-slug", null)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.DUPLICATE_STORE_SLUG);
	}

	@Test
	@DisplayName("같은_주소를_그대로_보내도_통과한다")
	void 주소_그대로() {
		// 설정 화면은 전체 폼을 보낸다. 주소를 안 건드렸다고 중복으로 튕기면 저장 자체가 안 된다
		sellerService.updateProfile(KAKAO, request("before-slug", "010-9999-0000"));

		assertThat(sellerRepository.findByKakaoId(KAKAO).orElseThrow().getStoreSlug())
				.isEqualTo("before-slug");
	}

	// ---------------------------------------------------------------- 못 바꾸는 것

	@Test
	@DisplayName("심사용_정보는_이_화면으로_바뀌지_않는다")
	void 심사_정보_보호() {
		sellerService.updateProfile(KAKAO, request("after-slug", "010-9999-0000"));

		Seller seller = sellerRepository.findByKakaoId(KAKAO).orElseThrow();
		// 승인의 근거다. 셀러가 갈아 끼울 수 있으면 심사가 무의미해진다
		assertThat(seller.getRepresentativeName()).isEqualTo("홍길동");
		assertThat(seller.getBusinessNo()).isEqualTo("1234567890");
		assertThat(seller.getSettlementAccount()).isEqualTo("우리 1002-000-000000");
		assertThat(seller.getEmail()).isEqualTo("seller@moeum.store");
	}

	// ---------------------------------------------------------------- 도우미

	private static SellerProfileRequest request(String slug, String publicContact) {
		return new SellerProfileRequest(slug, "모음 상점", "굿즈 선주문 전문",
				"https://instagram.com/moeum", publicContact, null);
	}
}
