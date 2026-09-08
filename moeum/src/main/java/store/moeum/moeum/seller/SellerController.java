package store.moeum.moeum.seller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.seller.dto.OnboardingRequest;
import store.moeum.moeum.seller.dto.SellerProfileRequest;
import store.moeum.moeum.seller.dto.SellerResponse;

import java.net.URI;

@Tag(name = "셀러", description = "온보딩 · 내 정보")
@RestController
@RequestMapping("/seller")
@RequiredArgsConstructor
public class SellerController {

	private final SellerService sellerService;
	private final ImageStorage imageStorage;

	/** 온보딩 제출 → review_status = PENDING */
	@Operation(summary = "셀러 온보딩 제출",
			description = """
					제출하면 심사 대기(PENDING) 상태가 된다. 승인돼야 판매를 시작할 수 있다.

					카카오 계정 하나당 셀러 하나다. 사업자번호와 정산계좌는 암호화해 저장하고
					구매자에게는 어떤 경로로도 나가지 않는다.
					""")
	@PostMapping("/onboarding")
	public ResponseEntity<SellerResponse> submitOnboarding(@LoginUser SessionUser user,
	                                                       @Valid @RequestBody OnboardingRequest request) {
		SellerResponse response = SellerResponse.from(sellerService.submitOnboarding(user.kakaoId(), request));
		return ResponseEntity.created(URI.create("/seller/me")).body(response);
	}

	@Operation(summary = "내 셀러 정보",
			description = "심사 상태와 설정값을 준다. 설정 화면의 초기값으로 쓴다.")
	@GetMapping("/me")
	public SellerResponse me(@LoginUser SessionUser user) {
		return withProfileImage(sellerService.getByKakaoId(user.kakaoId()));
	}

	/**
	 * 셀러 페이지(B0) 헤더 갱신 — 상점 이름 · 한 줄 소개 · 소셜 주소 · 프로필 이미지.
	 *
	 * 이미지는 {@code POST /sale-forms/images/upload-url} 로 받은 키를 그대로 넘긴다.
	 * 심사용 정보(대표자 실명 · 사업자번호 · 정산계좌)와 storeSlug 는 여기서 못 바꾼다.
	 */
	@Operation(summary = "셀러 설정 저장",
			description = """
					상점 이름 · 판매공간 주소 · 한 줄 소개 · 소셜 주소 · 문의 연락처 · 프로필 이미지를 바꾼다.
					전체 폼을 보내는 방식이라 바꾸지 않는 값도 현재 값을 그대로 실어야 한다.

					★ 판매공간 주소를 바꾸면 이미 뿌린 링크는 더 이상 열리지 않는다. 되돌릴 수 없다.
					남이 쓰는 주소면 409 다.

					심사용 정보(대표자 실명 · 사업자번호 · 정산계좌 · 이메일)는 여기서 바뀌지 않는다.
					""")
	@PutMapping("/me/profile")
	public SellerResponse updateProfile(@LoginUser SessionUser user,
	                                    @Valid @RequestBody SellerProfileRequest request) {
		return withProfileImage(sellerService.updateProfile(user.kakaoId(), request));
	}

	private SellerResponse withProfileImage(store.moeum.moeum.seller.domain.Seller seller) {
		return SellerResponse.from(seller, imageStorage.publicUrl(seller.getProfileImageKey()));
	}
}
