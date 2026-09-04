package store.moeum.moeum.seller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.seller.dto.OnboardingRequest;
import store.moeum.moeum.seller.dto.SellerResponse;

import java.net.URI;

@Tag(name = "셀러", description = "온보딩 · 내 정보")
@RestController
@RequestMapping("/seller")
@RequiredArgsConstructor
public class SellerController {

	private final SellerService sellerService;

	/** 온보딩 제출 → review_status = PENDING */
	@PostMapping("/onboarding")
	public ResponseEntity<SellerResponse> submitOnboarding(@LoginUser SessionUser user,
	                                                       @Valid @RequestBody OnboardingRequest request) {
		SellerResponse response = SellerResponse.from(sellerService.submitOnboarding(user.kakaoId(), request));
		return ResponseEntity.created(URI.create("/seller/me")).body(response);
	}

	@GetMapping("/me")
	public SellerResponse me(@LoginUser SessionUser user) {
		return SellerResponse.from(sellerService.getByKakaoId(user.kakaoId()));
	}
}
