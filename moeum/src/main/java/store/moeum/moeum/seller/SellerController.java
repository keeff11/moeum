package store.moeum.moeum.seller;

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
	@PostMapping("/onboarding")
	public ResponseEntity<SellerResponse> submitOnboarding(@LoginUser SessionUser user,
	                                                       @Valid @RequestBody OnboardingRequest request) {
		SellerResponse response = SellerResponse.from(sellerService.submitOnboarding(user.kakaoId(), request));
		return ResponseEntity.created(URI.create("/seller/me")).body(response);
	}

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
	@PutMapping("/me/profile")
	public SellerResponse updateProfile(@LoginUser SessionUser user,
	                                    @Valid @RequestBody SellerProfileRequest request) {
		return withProfileImage(sellerService.updateProfile(user.kakaoId(), request));
	}

	private SellerResponse withProfileImage(store.moeum.moeum.seller.domain.Seller seller) {
		return SellerResponse.from(seller, imageStorage.publicUrl(seller.getProfileImageKey()));
	}
}
