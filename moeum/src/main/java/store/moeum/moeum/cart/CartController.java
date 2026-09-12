package store.moeum.moeum.cart;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.cart.dto.CartAddRequest;
import store.moeum.moeum.cart.dto.CartResponse;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

import java.util.List;

@Tag(name = "장바구니", description = "담기 · 조회. 재고는 잡지 않는다")
@RestController
@RequestMapping("/me/cart")
@RequiredArgsConstructor
@Validated
public class CartController {

	private final CartService cartService;

	@Operation(summary = "장바구니에 담기",
			description = """
					담을 때 재고를 잡지 않는다. 재고는 주문을 만드는 시점에 확보된다.

					장바구니는 셀러당 하나다. 다른 셀러 상품을 담으면 그 셀러의 장바구니가 따로 생긴다 —
					배송비가 셀러 단위라 섞을 수 없다.
					""")
	@PostMapping("/items")
	public ResponseEntity<Void> add(@LoginUser SessionUser user, @Valid @RequestBody CartAddRequest request) {
		cartService.add(user, request);
		return ResponseEntity.status(201).build();
	}

	/** 셀러별로 나뉜 장바구니 전부 */
	@Operation(summary = "장바구니 조회",
			description = """
					담아둔 사이 마감·품절될 수 있어 항목마다 상태를 함께 준다.

					★ 이 상태는 조회 시점의 참고값이다. 최종 판정은 주문을 만들 때 이뤄진다.
					""")
	@GetMapping
	public List<CartResponse> list(@LoginUser SessionUser user) {
		return cartService.findMine(user);
	}

	@Operation(summary = "수량 변경")
	@PatchMapping("/items/{cartItemId}")
	public ResponseEntity<Void> changeQty(@LoginUser SessionUser user,
	                                      @Parameter(description = "장바구니 항목 id", example = "17")
	                                      @PathVariable Long cartItemId,
	                                      @Parameter(description = "바꿀 수량. 1 이상", example = "3")
	                                      @RequestParam @Min(value = 1, message = "1 이상이어야 합니다") int qty) {
		cartService.changeQty(user, cartItemId, qty);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "장바구니에서 빼기")
	@DeleteMapping("/items/{cartItemId}")
	public ResponseEntity<Void> remove(@LoginUser SessionUser user,
			@Parameter(description = "장바구니 항목 id", example = "17") @PathVariable Long cartItemId) {
		cartService.remove(user, cartItemId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * 결제 완료 화면이 부르는 정리 경로.
	 *
	 * <b>결제한 항목은 서버가 이미 뺀다.</b> 승인이 확정되는 순간 {@code CartCleaner} 가
	 * 같은 옵션의 항목을 지우고 빈 장바구니는 행째로 없앤다. 이 API 는 그 뒤에도
	 * 화면이 확실히 비우고 싶을 때를 위한 것이라 <b>비어 있어도 204</b> 다.
	 */
	@Operation(summary = "장바구니 비우기",
			description = """
					담긴 것을 전부 지운다. 셀러 하나만 지우려면 cartId 를 붙인다.

					★ 두 번 불러도 안전하다. 지울 것이 없어도 204 다 —
					  결제가 확정되면 서버가 산 항목을 이미 빼기 때문에, 결제 완료 화면이
					  이걸 부를 때는 대상이 없는 쪽이 정상이다.
					★ 남의 장바구니 id 를 보내도 지워지지 않는다. 내 것에서만 찾는다.
					""")
	@DeleteMapping
	public ResponseEntity<Void> clear(@LoginUser SessionUser user,
	                                  @Parameter(description = "비울 장바구니 id. 비우면 전부 지운다",
			                                  example = "3")
	                                  @RequestParam(required = false) Long cartId) {
		cartService.clear(user, cartId);
		return ResponseEntity.noContent().build();
	}
}
