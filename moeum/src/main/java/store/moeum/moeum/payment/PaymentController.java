package store.moeum.moeum.payment;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.dto.ConfirmRequest;
import store.moeum.moeum.payment.dto.PaySessionResponse;
import store.moeum.moeum.payment.dto.InProgressOrderResponse;
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
	@Operation(summary = "1차금 결제 세션 생성",
			description = """
					결제창을 띄울 세션을 만든다. 응답의 sessionId 를 point3 SDK 에 넘긴다.

					재고 홀드가 살아 있어야 하고, 마감된 상품은 여기서 막힌다.
					응답의 orderToken 은 이후 승인·조회·취소에서 계속 쓴다.
					""")
	@PostMapping("/checkout-sessions/{sessionToken}/pay")
	public PaySessionResponse pay(@LoginUser SessionUser user,
	                              @Parameter(description = "주문을 만들 때 받은 세션 토큰")
	                              @PathVariable String sessionToken) {
		return paymentService.pay(user, sessionToken);
	}

	/**
	 * 결제창에서 돌아온 뒤 승인을 요청한다.
	 *
	 * <b>응답이 PENDING 이면 실패가 아니다.</b> 결과를 확인 중이라는 뜻이고,
	 * 프론트는 다시 결제시키지 말고 상태 조회를 반복해야 한다. 재결제하면 이중 결제가 된다.
	 */
	@Operation(summary = "1차금 승인",
			description = """
					결제창에서 돌아온 뒤 서버가 실제로 출금을 확정한다. 이 호출이 없으면 결제가 완료되지 않는다.

					★ 응답이 PENDING 이면 실패가 아니다. 결과를 확인 중이라는 뜻이라
					다시 결제시키지 말고 `GET /orders/{orderToken}` 을 반복 조회해야 한다.
					재결제시키면 이중 결제가 된다.
					""")
	@PostMapping("/orders/{orderToken}/confirm")
	public PaymentResultResponse confirm(@LoginUser SessionUser user,
	                                     @Parameter(description = "주문 토큰. 결제 세션을 만들 때 받은 orderToken 이다", example = "otk_9f3a...")
	                                     @PathVariable String orderToken,
	                                     @Valid @RequestBody ConfirmRequest request) {
		return paymentService.confirm(user, orderToken, request.sessionId(), request.payerId());
	}

	/**
	 * 2차금 결제 세션. <b>모든 폼이 입고된 뒤에만 열린다.</b>
	 *
	 * 배송비가 묶음당 1회라 일부만 입고됐다고 청구하면 배송비를 나눌 방법이 없다.
	 * 응답의 payerId 를 SDK 의 customerKey 로 넘기면 인증 단계가 줄어든다.
	 */
	@Operation(summary = "2차금 결제 세션 생성",
			description = """
					잔금과 배송비를 청구할 결제창 세션을 만든다.

					★ 묶음의 모든 상품이 입고돼야 열린다. 배송비가 묶음당 1회라 일부만 입고됐다고
					청구하면 배송비를 나눌 방법이 없다. 아직이면 409 다.

					응답의 payerId 를 SDK 의 customerKey 로 넘기면 구매자 인증 단계가 줄어든다.
					""")
	@PostMapping("/orders/{orderToken}/second-payment")
	public PaySessionResponse paySecond(@LoginUser SessionUser user,
	                                    @Parameter(description = "주문 토큰")
	                                    @PathVariable String orderToken) {
		return paymentService.paySecond(user, orderToken);
	}

	/** 2차금 승인. 1차금과 같은 코드를 타고 phase 만 다르다 */
	@Operation(summary = "2차금 승인",
			description = "1차금 승인과 같다. PENDING 이면 재결제시키지 말고 상태 조회를 반복한다.")
	@PostMapping("/orders/{orderToken}/second-payment/confirm")
	public PaymentResultResponse confirmSecond(@LoginUser SessionUser user,
	                                           @Parameter(description = "주문 토큰")
	                                           @PathVariable String orderToken,
	                                           @Valid @RequestBody ConfirmRequest request) {
		return paymentService.confirmSecond(user, orderToken, request.sessionId());
	}

	/** 2차금 상태 조회 */
	@Operation(summary = "2차금 상태 조회",
			description = "부작용이 없다. 몇 번을 불러도 결제가 일어나지 않는다.")
	@GetMapping("/orders/{orderToken}/second-payment")
	public PaymentResultResponse secondStatus(@LoginUser SessionUser user,
	                                          @Parameter(description = "주문 토큰")
	                                          @PathVariable String orderToken) {
		return paymentService.secondStatus(user, orderToken);
	}

	/** 상태 조회. <b>부작용이 없다</b> (D-014) — 프론트는 이것만 반복하면 된다 */
	@Operation(summary = "1차금 상태 조회",
			description = """
					결제 결과를 확인한다. 부작용이 없어 몇 번을 불러도 안전하다.

					승인이 PENDING 으로 돌아왔을 때 이 API 만 반복하면 된다.
					""")
	@GetMapping("/orders/{orderToken}")
	public PaymentResultResponse status(@LoginUser SessionUser user,
	                                    @Parameter(description = "주문 토큰")
	                                    @PathVariable String orderToken) {
		return paymentService.status(user, orderToken);
	}

	/**
	 * 잃어버린 orderToken 되찾기 (D-042).
	 *
	 * <b>캐시하지 않는다.</b> 방금 끝난 결제가 목록에 남아 있으면 이어서 결제하라는
	 * 화면이 뜬다.
	 */
	@Operation(summary = "진행 중인 결제 목록",
			description = """
					내가 결제하다 만 건을 준다. <b>orderToken 은 /pay 응답으로만 내려가서</b>
					새로고침하거나 브라우저를 닫으면 되찾을 방법이 없다 — 이 API 가 그 경로다.

					pendingReason 으로 다음 행동이 갈린다.
					- AWAITING_PAYMENT — 결제창을 아직 끝내지 않았다. <b>이어서 결제해야 한다.</b>
                      폴링만 해서는 영원히 안 바뀐다
					- CONFIRMING — 승인 결과 대기 중이다. <b>다시 결제시키면 이중 결제다.</b>
                      상태 조회만 반복한다

					★ 비어 있는 것이 정상이다. 결제를 끝냈거나 시작하지 않은 구매자가 대부분이다.
					★ 만료 · 취소 · 실패한 건은 들어 있지 않다.
					""")
	@GetMapping("/me/orders/in-progress")
	public ResponseEntity<InProgressOrderResponse> inProgress(@LoginUser SessionUser user) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(paymentService.inProgress(user));
	}
}
