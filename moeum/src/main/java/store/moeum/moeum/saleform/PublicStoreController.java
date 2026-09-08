package store.moeum.moeum.saleform;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
 * 경로의 {@code storeSlug} 가 셀러가 뿌리는 공개 주소다 (meoum.store/{storeSlug}).
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
	 */
	@Operation(
			summary = "셀러 페이지 조회",
			description = """
					셀러 프로필과 판매 상품 목록을 한 번에 준다. 로그인이 필요 없다.

					- 심사가 끝나지 않은 셀러는 404 다 (상점이 아직 공개되지 않았다는 뜻)
					- 작성 중(DRAFT)인 판매 폼은 목록에 나오지 않는다
					- 마감된 상품도 보여주되 판매 중인 것이 위에 온다
					- 카드의 하트는 이 응답에 없다. `GET /me/wishlist` 로 따로 받아 칠한다
					- 재고·모집 현황이 실려 있어 캐시하지 않는다 (no-store)
					""")
	@GetMapping("/stores/{storeSlug}")
	public ResponseEntity<StorePageResponse> page(

			@Parameter(description = "판매공간 주소. meoum.store/{이 값} 의 그 값이다", example = "moeum-store")
			@PathVariable String storeSlug,

			@Parameter(description = "판매 유형 탭. 비우면 '전체', GROUP=공동구매, SOLO=단독 판매")
			@RequestParam(required = false) SaleType saleType,

			@Parameter(description = "상품명 검색어. 부분 일치로 찾는다. % 나 _ 를 쳐도 글자로 취급한다",
					example = "아크릴")
			@RequestParam(required = false) String q,

			@Parameter(description = "페이지 번호. 0부터 시작한다", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "페이지 크기. 최대 50이고 넘기면 50으로 줄어든다", example = "20")
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(publicStoreService.page(storeSlug, saleType, q, page, size));
	}
}
