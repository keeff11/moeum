package store.moeum.moeum.seller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.seller.dto.SellerResponse;

/**
 * 심사 처리. 로드맵상 "일단 수동 API".
 *
 * 아직 운영자 인증이 없다. 운영 프로파일에 노출하기 전에 접근 제어를 반드시 붙여야 한다.
 */
@Tag(name = "셀러 심사(관리자)", description = "수동 승인 · 반려. 운영 노출 전 접근 제어 필요")
@RestController
@RequestMapping("/admin/sellers")
@RequiredArgsConstructor
public class AdminSellerController {

	private final SellerService sellerService;

	@PostMapping("/{sellerId}/approve")
	public SellerResponse approve(@PathVariable Long sellerId) {
		return SellerResponse.from(sellerService.approve(sellerId));
	}

	@PostMapping("/{sellerId}/reject")
	public SellerResponse reject(@PathVariable Long sellerId) {
		return SellerResponse.from(sellerService.reject(sellerId));
	}
}
