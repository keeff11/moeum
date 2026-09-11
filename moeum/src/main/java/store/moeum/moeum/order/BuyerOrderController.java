package store.moeum.moeum.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.dto.BuyerOrderPageResponse;
import store.moeum.moeum.saleform.domain.SaleType;

/**
 * 구매자 주문 목록 (와이어프레임 B13 — 나의 구매 목록).
 *
 * 한 줄이 묶음 하나다 (D-033). 카드를 누르면 {@code orderToken} 으로 상세(B8)로 간다.
 */
@Tag(name = "구매자 주문", description = "나의 구매 목록")
@RestController
@RequestMapping("/me/orders")
@RequiredArgsConstructor
public class BuyerOrderController {

	private final BuyerOrderService buyerOrderService;

	/**
	 * <b>캐시하지 않는다.</b> 카드마다 결제·입고 상태와 취소 가능 여부가 실려 있어
	 * 지난 값을 보여 주면 이미 낸 잔금을 또 내라고 하거나, 못 하는 취소 버튼을 띄운다.
	 */
	@Operation(summary = "나의 구매 목록",
			description = """
					내 주문을 최신순으로 준다. 한 줄이 결제 한 건(묶음)이고,
					장바구니로 여러 상품을 담은 주문은 제목이 '외 N건' 으로 접힌다.

					탭은 saleType 으로 가른다 — 비우면 전체, GROUP 은 공동구매, SOLO 는 단독판매.

					★ 결제 전 장바구니 세션과 만료된 건은 나오지 않는다. 취소한 주문은 남는다.
					★ cancelable 은 우리 취소 규칙(D-025)만 본 값이라 확정이 아니다.
					  버튼을 누른 뒤 GET /orders/{orderToken}/refundable 이 최종 판단을 한다.
					""")
	@GetMapping
	public ResponseEntity<BuyerOrderPageResponse> list(
			@LoginUser SessionUser user,

			@Parameter(description = "판매 유형 탭. 비우면 전체다", example = "GROUP")
			@RequestParam(required = false) SaleType saleType,

			@Parameter(description = "페이지 번호. 0부터 시작한다", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "페이지 크기. 최대 50", example = "20")
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(buyerOrderService.list(user.kakaoId(), saleType, page, size));
	}
}
