package store.moeum.moeum.order;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
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

	@Operation(summary = "주문 만들기 (재고 홀드)",
			description = """
					옵션과 수량을 확정하는 순간 재고를 잡는다. 배송지 입력 전에 부른다 —
					주소를 다 적은 뒤에 품절을 통보받는 상황을 피하기 위해서다.

					★ 홀드는 15분이다. 그 안에 결제하지 않으면 자리가 풀린다.
					응답의 remainingSeconds 로 타이머를 그린다.

					품절이면 409 이고, 재시도해도 결과는 같다.
					""")
	@PostMapping
	public ResponseEntity<OrderGroupResponse> create(@LoginUser SessionUser user,
	                                                 @Valid @RequestBody OrderCreateRequest request) {
		OrderGroupResponse response = orderService.place(user, request);
		return ResponseEntity.created(URI.create("/checkout-sessions/" + response.sessionToken()))
				.body(response);
	}

	@Operation(summary = "주문 조회",
			description = "홀드 남은 시간과 금액을 준다. 결제 화면 진입 때 부른다.")
	@GetMapping("/{sessionToken}")
	public OrderGroupResponse get(@LoginUser SessionUser user,
			@Parameter(description = "주문을 만들 때 받은 세션 토큰") @PathVariable String sessionToken) {
		return orderService.findBySessionToken(user, sessionToken);
	}

	/** 이탈 시 해제. 멱등하다 */
	@Operation(summary = "주문 취소 (홀드 해제)",
			description = """
					결제하지 않고 나갈 때 부른다. 잡아둔 재고를 즉시 돌려놓아 다른 사람이 살 수 있게 한다.

					두 번 불러도 안전하다. 부르지 않아도 15분 뒤 자동으로 풀린다.
					""")
	@PostMapping("/{sessionToken}/release")
	public ResponseEntity<Void> release(@LoginUser SessionUser user,
			@Parameter(description = "주문을 만들 때 받은 세션 토큰") @PathVariable String sessionToken) {
		orderService.release(user, sessionToken);
		return ResponseEntity.noContent().build();
	}
}
