package store.moeum.moeum.payment.refund;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
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
import store.moeum.moeum.payment.refund.dto.OrderRefundRequest;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;
import store.moeum.moeum.payment.refund.dto.RefundableResponse;

/**
 * 구매자 취소 API.
 *
 * 소유권은 서비스가 주문의 buyer 와 세션 사용자를 대조해 확인한다 —
 * orderToken 을 아는 것만으로는 남의 주문을 취소할 수 없다.
 */
@Tag(name = "취소·환불", description = "취소 가능 조회 · 폼 단위 부분 취소")
@RestController
@RequiredArgsConstructor
public class RefundController {

	private final OrderRefundService orderRefundService;

	/**
	 * 취소 가능 여부와 폼별 환불 예정액.
	 *
	 * <b>부작용이 없다.</b> 주문 상세 화면에서 그대로 불러도 된다.
	 */
	@Operation(summary = "취소 가능 조회",
			description = """
					폼별로 취소할 수 있는지와 돌려받을 금액을 준다. 부작용이 없어 주문 상세 화면에서 그대로 불러도 된다.

					- 공동구매는 발주 시작 전, 단독 판매는 발송 전까지 취소할 수 있다
					- 배송비는 남은 폼을 전부 취소할 때만 함께 돌아온다
					- eobOpensAt 이 있으면 PG 정산 시간대라 지금은 취소가 막혀 있다는 뜻이다
					""")
	@GetMapping("/orders/{orderToken}/refundable")
	public RefundableResponse refundable(@LoginUser SessionUser user,
	                                     @Parameter(description = "주문 토큰")
	                                     @PathVariable String orderToken) {
		return orderRefundService.refundable(user, orderToken);
	}

	/**
	 * 취소한다. 본문의 {@code orderId} 를 채우면 그 폼만, 비우면 남은 폼 전부다.
	 *
	 * <b>응답이 PROCESSING 이면 실패가 아니다.</b> 취소는 멱등하지 않아 다시 보내면
	 * 두 번 환불된다. 프론트는 재시도 버튼 대신 취소 가능 조회를 다시 불러야 한다.
	 */
	@Operation(summary = "주문 취소",
			description = """
					본문의 orderId 를 채우면 그 폼만, 비우면 남은 폼 전부를 취소한다.
					금액은 서버가 계산하므로 보내지 않는다.

					★ 응답이 PROCESSING 이면 실패가 아니다. 취소는 다시 보내면 두 번 환불되므로
					재시도 버튼 대신 취소 가능 조회를 다시 불러야 한다.
					""")
	@PostMapping("/orders/{orderToken}/refund")
	public OrderRefundResponse refund(@LoginUser SessionUser user,
	                                  @Parameter(description = "주문 토큰")
	                                  @PathVariable String orderToken,
	                                  @Valid @RequestBody OrderRefundRequest request) {
		return orderRefundService.refund(user, orderToken, request.orderId(), request.reason());
	}
}
