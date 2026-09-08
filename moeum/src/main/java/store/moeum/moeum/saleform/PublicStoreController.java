package store.moeum.moeum.saleform;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.StorePageResponse;

/**
 * 셀러 페이지 (B0). <b>인증이 없다.</b>
 *
 * 경로의 {@code storeSlug} 가 셀러가 뿌리는 공개 주소다 (meoum.kr/{storeSlug}).
 */
@Tag(name = "셀러 페이지(공개)", description = "셀러 프로필 + 판매 상품 목록")
@RestController
@RequiredArgsConstructor
public class PublicStoreController {

	private final PublicStoreService publicStoreService;

	/**
	 * 헤더 + 상품 목록. <b>캐시하지 않는다.</b>
	 *
	 * 카드마다 모집 현황과 재고가 실려 있어 상세({@code /products/{id}})처럼 캐시할 수 없다.
	 * 캐시된 재고를 보고 들어가면 홀드에서 품절로 튕긴다 (D-029).
	 *
	 * @param saleType 전체는 생략, 탭에 따라 GROUP · SOLO
	 * @param q        상품명 검색어
	 */
	@GetMapping("/stores/{storeSlug}")
	public ResponseEntity<StorePageResponse> page(
			@PathVariable String storeSlug,
			@RequestParam(required = false) SaleType saleType,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(publicStoreService.page(storeSlug, saleType, q, page, size));
	}
}
