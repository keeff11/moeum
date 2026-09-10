package store.moeum.moeum.seller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.seller.dto.SellerHomeResponse;

/**
 * 셀러 홈 (와이어프레임 G1).
 *
 * 화면 하나에 요청 하나다. 처리할 주문 · 진행 중 판매 · 최근 주문을 따로 부르게 두면
 * 홈을 열 때마다 왕복이 셋이고 셋의 시점이 어긋난다.
 */
@Tag(name = "셀러 홈", description = "대시보드 집계")
@RestController
@RequestMapping("/seller/home")
@RequiredArgsConstructor
public class SellerHomeController {

	private final SellerHomeService sellerHomeService;

	/**
	 * <b>캐시하지 않는다.</b> 배지 숫자와 모집 현황이 실려 있다 — 지난 값을 보여 주면
	 * 청구할 것이 남았는데 0으로 보인다. G6 목록과 같은 이유다 (D-029).
	 */
	@Operation(summary = "셀러 홈 대시보드",
			description = """
					셀러 홈(G1) 한 장에 필요한 것을 한 번에 준다.

					- todo — 지금 손을 대야 하는 주문 건수. 2차금 미납은 S10 일괄 청구의
					  대상 건수와 같고, G6 의 탭 배지와도 같은 집계다
					- activeSales — 판매 중·일시중지인 판매 카드. 마감이 임박한 것부터다.
					  모집 수량과 D-day 가 실린다
					- recentOrders — 최근 주문 5건. G6 목록의 첫 장과 같은 카드다

					★ activeSales 가 비면 진행 중인 판매가 없다는 뜻이다 — 화면은 빈 상태를 그린다.
					★ 모집 숫자는 셀러에게 늘 보인다. 진행 현황 공개를 꺼도 그건 구매자에게만 감추는 설정이다.
					""")
	@GetMapping
	public ResponseEntity<SellerHomeResponse> home(@LoginUser SessionUser user) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerHomeService.home(user.kakaoId()));
	}
}
