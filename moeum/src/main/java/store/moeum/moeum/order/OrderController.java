package store.moeum.moeum.order;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.dto.OrderGroupResponse;

import java.net.URI;

/**
 * checkout_session = order_group(CREATED). 별도 테이블이 아니다.
 * B2 옵션·수량 확정 시 생성되고 그 순간 재고가 홀드된다 (D-001).
 */
@Tag(name = "주문·재고 홀드", description = "B2 확정 시 재고 확보. 외부 결제 호출은 여기 없다")
@RestController
@RequestMapping("/checkout-sessions")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;

	@PostMapping
	public ResponseEntity<OrderGroupResponse> create(@LoginUser SessionUser user,
	                                                 @Valid @RequestBody OrderCreateRequest request) {
		OrderGroupResponse response = orderService.place(user, request);
		return ResponseEntity.created(URI.create("/checkout-sessions/" + response.sessionToken()))
				.body(response);
	}

	@GetMapping("/{sessionToken}")
	public OrderGroupResponse get(@LoginUser SessionUser user, @PathVariable String sessionToken) {
		return orderService.findBySessionToken(user, sessionToken);
	}

	/** 이탈 시 해제. 멱등하다 */
	@PostMapping("/{sessionToken}/release")
	public ResponseEntity<Void> release(@LoginUser SessionUser user, @PathVariable String sessionToken) {
		orderService.release(user, sessionToken);
		return ResponseEntity.noContent().build();
	}
}
