package store.moeum.moeum.cart;

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

	@PostMapping("/items")
	public ResponseEntity<Void> add(@LoginUser SessionUser user, @Valid @RequestBody CartAddRequest request) {
		cartService.add(user, request);
		return ResponseEntity.status(201).build();
	}

	/** 셀러별로 나뉜 장바구니 전부 */
	@GetMapping
	public List<CartResponse> list(@LoginUser SessionUser user) {
		return cartService.findMine(user);
	}

	@PatchMapping("/items/{cartItemId}")
	public ResponseEntity<Void> changeQty(@LoginUser SessionUser user,
	                                      @PathVariable Long cartItemId,
	                                      @RequestParam @Min(value = 1, message = "1 이상이어야 합니다") int qty) {
		cartService.changeQty(user, cartItemId, qty);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/items/{cartItemId}")
	public ResponseEntity<Void> remove(@LoginUser SessionUser user, @PathVariable Long cartItemId) {
		cartService.remove(user, cartItemId);
		return ResponseEntity.noContent().build();
	}
}
