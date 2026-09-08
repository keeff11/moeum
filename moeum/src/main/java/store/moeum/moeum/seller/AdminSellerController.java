package store.moeum.moeum.seller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
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

	@Operation(summary = "셀러 승인 (운영자)",
			description = "승인해야 판매 폼을 만들고 셀러 페이지가 공개된다.")
	@PostMapping("/{sellerId}/approve")
	public SellerResponse approve(@Parameter(description = "셀러 id", example = "1") @PathVariable Long sellerId) {
		return SellerResponse.from(sellerService.approve(sellerId));
	}

	@Operation(summary = "셀러 반려 (운영자)")
	@PostMapping("/{sellerId}/reject")
	public SellerResponse reject(@Parameter(description = "셀러 id", example = "1") @PathVariable Long sellerId) {
		return SellerResponse.from(sellerService.reject(sellerId));
	}
}
