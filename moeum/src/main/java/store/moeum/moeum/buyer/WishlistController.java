package store.moeum.moeum.buyer;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.buyer.dto.WishlistResponse;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

/**
 * 찜(하트). 전부 로그인이 필요하다.
 *
 * <b>셀러 페이지 목록과 따로 받는다.</b> 목록 응답에 찜 여부를 실으면 그 응답이
 * 사용자별로 갈려 모두에게 같은 것을 줄 수 없게 된다 (D-029).
 * 프론트가 목록을 그린 뒤 이 목록으로 하트만 칠한다.
 */
@Tag(name = "찜", description = "찜 목록 조회 · 등록 · 해제")
@RestController
@RequestMapping("/me/wishlist")
@RequiredArgsConstructor
public class WishlistController {

	private final WishlistService wishlistService;

	/** 하트를 칠할 판매 폼 id 목록. 최근에 찜한 것이 앞이다 */
	@GetMapping
	public WishlistResponse mine(@LoginUser SessionUser user) {
		return WishlistResponse.of(wishlistService.saleFormIds(user));
	}

	/** 찜한다. <b>멱등하다</b> — 이미 찜한 폼이어도 204 다 */
	@PutMapping("/{saleFormId}")
	public ResponseEntity<Void> add(@LoginUser SessionUser user, @PathVariable Long saleFormId) {
		wishlistService.add(user, saleFormId);
		return ResponseEntity.noContent().build();
	}

	/** 찜을 뗀다. <b>멱등하다</b> — 찜한 적 없어도 204 다 */
	@DeleteMapping("/{saleFormId}")
	public ResponseEntity<Void> remove(@LoginUser SessionUser user, @PathVariable Long saleFormId) {
		wishlistService.remove(user, saleFormId);
		return ResponseEntity.noContent().build();
	}
}
