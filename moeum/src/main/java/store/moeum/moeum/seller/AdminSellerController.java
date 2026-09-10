package store.moeum.moeum.seller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.dto.SellerApplicantPageResponse;
import store.moeum.moeum.seller.dto.SellerResponse;

/**
 * 심사 처리. 로드맵상 "일단 수동 API".
 *
 * <b>운영자 인증을 앱에 붙이지 않는다 (D-039).</b> 셀러 가입이 오면 기획측이 사람 눈으로
 * 확인하고 허가하는 방식으로 정했다. 그래서 여기 필요한 것은 신청자 목록과 수락 두 가지고,
 * 접근 제어는 프록시가 맡는다 — {@code /admin/*} 은 {@code deploy/Caddyfile} 이 404 로
 * 막는다. <b>이 API 를 밖에서 부를 수 있게 하려면 Caddy 를 먼저 손봐야 한다.</b>
 * 지금 상태로는 인스턴스 안에서만 호출된다.
 */
@Tag(name = "셀러 심사(관리자)", description = "신청자 목록 · 수동 승인 · 반려. 프록시가 외부 호출을 막는다")
@RestController
@RequestMapping("/admin/sellers")
@RequiredArgsConstructor
public class AdminSellerController {

	private final SellerService sellerService;

	/**
	 * 신청자 목록.
	 *
	 * <b>캐시하지 않는다.</b> 방금 승인한 건이 목록에 남아 있으면 두 번 승인하게 된다.
	 */
	@Operation(summary = "셀러 심사 신청자 목록 (운영자)",
			description = """
					심사할 셀러를 먼저 신청한 순서로 준다. 심사는 대기열이라
					최신순으로 두면 오래된 신청이 바닥에 깔린다.

					★ 사업자등록번호가 이 응답에만 들어 있다. 심사의 판단 근거라서다 —
					셀러 본인·구매자용 응답에는 담기지 않는다.
					★ 정산계좌는 담지 않는다. 심사가 아니라 정산에 쓰는 값이다.
					""")
	@GetMapping
	public ResponseEntity<SellerApplicantPageResponse> applicants(

			@Parameter(description = "심사 상태. 비우면 대기 중(PENDING)만 준다. ALL 처럼 전체를 보려면 null 로 두지 말고 상태를 하나씩 지정한다",
					example = "PENDING")
			@RequestParam(required = false, defaultValue = "PENDING") ReviewStatus status,

			@Parameter(description = "페이지 번호. 0부터 시작한다", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "페이지 크기. 최대 100", example = "20")
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerService.applicants(status, page, size));
	}

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
