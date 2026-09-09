package store.moeum.moeum.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.dto.SellerOrderDetailResponse;
import store.moeum.moeum.order.dto.SecondChargeResponse;
import store.moeum.moeum.order.dto.SellerOrderPageResponse;

/**
 * 셀러 주문 목록 (와이어프레임 G6).
 *
 * 한 줄이 묶음 하나다 (D-033). 셀러 하단 탭 [주문]과 판매별 주문 목록(G5-O)이
 * 같은 엔드포인트를 쓰고, 판매별은 {@code saleFormId} 만 더 붙인다.
 */
@Tag(name = "셀러 주문", description = "주문 목록 · 상세")
@RestController
@RequestMapping("/seller/orders")
@RequiredArgsConstructor
public class SellerOrderController {

	private final SellerOrderService sellerOrderService;
	private final SecondChargeService secondChargeService;

	/**
	 * 목록. <b>캐시하지 않는다</b> — 카드마다 결제·입고 상태가 실려 있어 D-029 와 이유가 같다.
	 */
	@Operation(summary = "셀러 주문 목록",
			description = """
					내 판매로 들어온 주문을 최신순으로 준다. 한 줄이 결제 한 건(묶음)이고,
					장바구니로 여러 상품을 담은 주문은 제목이 '외 N건' 으로 접힌다.

					상태 탭 다섯 칸의 건수를 counts 로 함께 준다. 탭을 바꿔도 이 값은 그대로다 —
					검색어나 판매별 필터를 걸면 그 조건이 반영된 숫자로 바뀐다.

					★ 결제 전 장바구니 세션과 만료된 건은 어느 탭에도 나오지 않는다.
					★ 발송 완료는 송장 등록 기능이 아직 없어 당분간 항상 0건이다.
					""")
	@GetMapping
	public ResponseEntity<SellerOrderPageResponse> list(
			@LoginUser SessionUser user,

			@Parameter(description = "상태 탭. 비우면 전체다", example = "SECOND_UNPAID")
			@RequestParam(required = false) SellerOrderTab tab,

			@Parameter(description = "판매별 필터. 이 판매 폼이 들어 있는 주문만 본다", example = "12")
			@RequestParam(required = false) Long saleFormId,

			@Parameter(description = "검색어. 주문번호 · 수령인 · 상품명 · 판매 제목을 부분 일치로 찾는다. "
					+ "% 나 _ 를 쳐도 글자로 취급한다", example = "김서연")
			@RequestParam(required = false) String q,

			@Parameter(description = "페이지 번호. 0부터 시작한다", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "페이지 크기. 최대 50이고 넘기면 50으로 줄어든다", example = "20")
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerOrderService.list(user.kakaoId(), tab, saleFormId, q, page, size));
	}

	/**
	 * 청구 버튼을 누르기 전에 보여 줄 값.
	 *
	 * <b>이 경로가 {@code /{orderNo}} 보다 먼저 잡힌다</b> — 스프링은 변수 자리보다
	 * 글자 그대로 일치하는 경로를 우선한다. 주문번호는 ORD- 로 시작해서 실제로 겹치지도 않는다.
	 */
	@Operation(summary = "2차금 청구 대상 미리보기",
			description = """
					지금 2차금을 청구할 수 있는 주문 수와 금액 합계를 준다.
					대상은 주문 목록의 '2차금 미납' 탭과 같은 기준이다 — 전 폼이 입고된 묶음만이다.

					★ 최근 24시간 안에 이미 청구한 건은 chargeableCount 에서 빠진다.
					★ 금액에는 취소된 폼의 잔금이 들어 있지 않다.
					""")
	@GetMapping("/second-charge")
	public ResponseEntity<SecondChargeResponse.Preview> secondChargePreview(
			@LoginUser SessionUser user,

			@Parameter(description = "이 판매 건만 청구 대상으로 본다. 비우면 내 주문 전체", example = "12")
			@RequestParam(required = false) Long saleFormId) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(secondChargeService.preview(user.kakaoId(), saleFormId));
	}

	/** 알림을 다시 내보내고 이력을 남긴다. 주문 상태는 바뀌지 않는다 */
	@Operation(summary = "2차금 일괄 청구",
			description = """
					대상 주문마다 2차금 청구 알림을 다시 내보내고 청구 이력을 남긴다.

					★ 출금이 아니다. 자동출금이 없어서 실제 결제는 구매자가 링크로 들어와 승인해야 한다.
					★ 주문 상태는 바뀌지 않는다. 구매자가 결제를 마쳐야 넘어간다.
					★ 최근 24시간 안에 이미 청구한 건은 건너뛰고 skippedCount 로 센다.
					""")
	@PostMapping("/second-charge")
	public SecondChargeResponse.Result secondCharge(
			@LoginUser SessionUser user,

			@Parameter(description = "이 판매 건만 청구한다. 비우면 대상 전체", example = "12")
			@RequestParam(required = false) Long saleFormId) {

		return secondChargeService.charge(user.kakaoId(), saleFormId);
	}

	/** 카드를 누르면 열리는 드로어. 배송지 본문은 담기지 않는다 */
	@Operation(summary = "셀러 주문 상세",
			description = """
					결제 · 상품/옵션 · 구매자 정보 · 다음 단계 네 칸을 준다.

					상품명과 옵션명은 주문 시점 스냅샷이라 그 뒤에 폼을 수정해도 바뀌지 않는다.

					★ 구매자 연락처는 가운데를 가려서 준다. 배송지 본문은 담기지 않는다.
					""")
	@GetMapping("/{orderNo}")
	public ResponseEntity<SellerOrderDetailResponse> detail(
			@LoginUser SessionUser user,

			@Parameter(description = "주문번호. 목록 카드에 찍힌 그 값이다", example = "ORD-260830-41")
			@PathVariable String orderNo) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerOrderService.detail(user.kakaoId(), orderNo));
	}
}
