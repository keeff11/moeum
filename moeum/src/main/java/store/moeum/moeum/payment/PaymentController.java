package store.moeum.moeum.payment;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.dto.ConfirmRequest;
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.dto.PaymentResultResponse;

/**
 * 1차금 결제 API.
 *
 * 전부 로그인이 필요하고, 소유권은 서비스가 주문의 buyer 와 세션 사용자를 대조해 확인한다.
 * 토큰을 아는 것만으로는 남의 주문을 결제하거나 조회할 수 없다.
 */
@Tag(name = "결제", description = "1차금 세션 생성 · 승인 · 상태 조회")
@RestController
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService paymentService;

	/** 결제창을 띄울 세션을 만든다. 응답의 sessionId 를 SDK 에 넘긴다 */
	@PostMapping("/checkout-sessions/{sessionToken}/pay")
	public PaySessionResponse pay(@LoginUser SessionUser user, @PathVariable String sessionToken) {
		return paymentService.pay(user, sessionToken);
	}

	/**
	 * 결제창에서 돌아온 뒤 승인을 요청한다.
	 *
	 * <b>응답이 PENDING 이면 실패가 아니다.</b> 결과를 확인 중이라는 뜻이고,
	 * 프론트는 다시 결제시키지 말고 상태 조회를 반복해야 한다. 재결제하면 이중 결제가 된다.
	 */
	@PostMapping("/orders/{orderToken}/confirm")
	public PaymentResultResponse confirm(@LoginUser SessionUser user,
	                                     @PathVariable String orderToken,
	                                     @Valid @RequestBody ConfirmRequest request) {
		return paymentService.confirm(user, orderToken, request.sessionId());
	}

	/** 상태 조회. <b>부작용이 없다</b> (D-014) — 프론트는 이것만 반복하면 된다 */
	@GetMapping("/orders/{orderToken}")
	public PaymentResultResponse status(@LoginUser SessionUser user, @PathVariable String orderToken) {
		return paymentService.status(user, orderToken);
	}
}
