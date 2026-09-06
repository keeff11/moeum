package store.moeum.moeum.saleform;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.saleform.dto.ProductAvailabilityResponse;
import store.moeum.moeum.saleform.dto.ProductDetailResponse;

/**
 * 구매자용 공개 상품 API. <b>인증이 없다.</b>
 *
 * {@code @LoginUser} 를 붙이지 않는 것으로 충분하다 — 세션이 없어도 통과한다.
 * Origin 검증 필터도 GET 은 지나보낸다 (상태를 바꾸지 않으므로).
 *
 * 경로의 {@code id} 는 판매 폼 id 다. 재고 · 목표수량 · 마감이 폼 단위라
 * 구매자가 보는 '상품'의 실체가 판매 폼이다.
 */
@Tag(name = "상품(공개)", description = "구매자용 상품 조회 · 재고 현황")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class PublicProductController {

	private final PublicProductService publicProductService;

	/**
	 * 정적 정보. 프론트가 Data Cache + revalidateTag 로 캐시한다.
	 * 재고처럼 초 단위로 바뀌는 값은 여기 담지 않는다.
	 */
	@GetMapping("/{id}")
	public ProductDetailResponse detail(@PathVariable Long id) {
		return publicProductService.detail(id);
	}

	/**
	 * 모집 현황 · 재고. <b>절대 캐시하지 않는다.</b>
	 *
	 * 캐시된 재고를 보고 구매를 누르면 홀드에서 품절로 튕긴다.
	 * 헤더를 응답에 직접 실어 중간 프록시까지 막는다 — 프론트 설정에만 맡기지 않는다.
	 */
	@GetMapping("/{id}/availability")
	public ResponseEntity<ProductAvailabilityResponse> availability(@PathVariable Long id) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(publicProductService.availability(id));
	}
}
