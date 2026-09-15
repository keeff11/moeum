package store.moeum.moeum.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.payment.domain.SellerPaymentStatus;
import store.moeum.moeum.payment.dto.SellerPaymentDetailResponse;
import store.moeum.moeum.payment.dto.SellerPaymentPageResponse;
import store.moeum.moeum.payment.refund.dto.OrderRefundRequest;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;

/**
 * 셀러 결제/정산 탭 (와이어프레임 G10 · G10 상세 드로어 · S14) — D-059.
 *
 * <b>줄 하나가 결제 한 건이다</b> — 주문묶음 × 차수. 셀러 주문 목록(G6)이 묶음 하나를
 * 한 줄로 접는 것과 일부러 다르다: 거기서 셀러가 보는 것은 "이 주문을 어떻게 처리할까"
 * 이고 여기서 보는 것은 "언제 얼마가 들어왔고 그중 무엇이 되돌아갔는가" 다.
 *
 * <b>결제 취소는 구매자 취소와 같은 엔진·같은 구간 정책을 탄다.</b> 셀러라고 발주가
 * 끝난 공구를 되돌릴 수 있는 것이 아니다. 누가 걸었는지는 {@code refund.requested_by}
 * 에 SELLER 로 남는다 — 돌려준 돈은 되돌릴 수 없어서 흔적이 있어야 한다.
 */
@Tag(name = "셀러 결제/정산", description = "결제 내역 · 상세 · 결제 취소 · 정산 후 환불 처리")
@RestController
@RequestMapping("/seller/payments")
@RequiredArgsConstructor
public class SellerPaymentController {

	private final SellerPaymentService sellerPaymentService;

	/**
	 * 목록. <b>캐시하지 않는다</b> — 줄마다 취소 상태가 실려 있어 G6 와 이유가 같다.
	 */
	@Operation(summary = "셀러 결제 내역",
			description = """
					내 판매로 들어온 결제를 결제한 시각 기준 최신순으로 준다.
					한 줄이 결제 한 건(묶음 × 차수)이라, 같은 주문의 1차금과 2차금이 두 줄로 나온다.

					칩 여섯 칸의 건수를 counts 로 함께 준다. 칩을 바꿔도 이 값은 그대로다 —
					검색어나 판매별 필터를 걸면 그 조건이 반영된 숫자로 바뀐다.

					★ 결제창까지 가지 않은 세션과 만료된 묶음은 어느 칩에도 나오지 않는다.
					★ '정산 완료'는 <b>시스템으로 되돌릴 수 없어 직접 이체해야 하는 건</b>이다.
					  point3 가 취소를 거절했을 때만 알 수 있어, 정산됐지만 아무도 취소를
					  시도하지 않은 결제는 여기 잡히지 않는다.
					★ '취소 처리중'은 실패가 아니다. 결과를 모르는 것이고 서버 배치가 끝낸다.
					★ counts.pending('확인 중')은 칩이 없다. 전체에만 섞여 있어 칩 숫자의 합이
					  all 보다 작을 수 있고, 그게 정상이다.
					""")
	@GetMapping
	public ResponseEntity<SellerPaymentPageResponse> list(
			@LoginUser SessionUser user,

			@Parameter(description = "칩. 비우면 전체다", example = "SETTLED")
			@RequestParam(required = false) SellerPaymentStatus status,

			@Parameter(description = "판매별 필터. 이 판매 폼이 들어 있는 주문의 결제만 본다",
					example = "12")
			@RequestParam(required = false) Long saleFormId,

			@Parameter(description = "검색어. 주문번호 · 수령인 · 상품명 · 판매 제목을 부분 일치로 찾는다. "
					+ "% 나 _ 를 쳐도 글자로 취급한다", example = "아크릴")
			@RequestParam(required = false) String q,

			@Parameter(description = "페이지 번호. 0부터 시작한다", example = "0")
			@RequestParam(defaultValue = "0") int page,

			@Parameter(description = "페이지 크기. 최대 50이고 넘기면 50으로 줄어든다", example = "20")
			@RequestParam(defaultValue = "20") int size) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerPaymentService.list(user.kakaoId(), status, saleFormId, q, page, size));
	}

	/** 줄을 누르면 열리는 드로어. 환불 처리(S14) 화면도 같은 응답을 쓴다 */
	@Operation(summary = "셀러 결제 상세",
			description = """
					판매 · 상품/옵션 · 구매자 · 구분 · 금액 · 일시 · 수단 · 상태와,
					[결제 취소] 버튼을 켤지 말지를 준다.

					정산 후 직접 환불(S14) 건이면 manualRefund 가 채워져 나온다 —
					환불 신청 시각 · 금액 · 처리 여부 · 보낼 계좌다.

					★ cancel.cancelable 이 false 면 버튼을 끄고 blockedReason 을 그대로 보여 준다.
					  판정은 구매자 취소와 같다 — 공동구매는 발주 전, 단독 판매는 발송 전까지다.
					★ blockedUntil 이 있으면 PG 정산 시간대(23:30~00:30)라 잠깐 막힌 것이다.
					  '실패'가 아니라 '그 시각 이후에 가능'으로 안내한다.
					★ 수단(method)은 <b>항상 비어 있다</b>. point3 가 승인 응답에 주지 않아
					  서버에 저장된 값이 없다.
					★ 계좌번호는 아직 이체하지 않은 건에만 실린다. 처리가 끝나면 빠진다.
					""")
	@GetMapping("/{paymentNo}")
	public ResponseEntity<SellerPaymentDetailResponse> detail(
			@LoginUser SessionUser user,

			@Parameter(description = "결제번호. 목록 줄에 찍힌 그 값이다", example = "ORD-260828-92-1")
			@PathVariable String paymentNo) {

		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(sellerPaymentService.detail(user.kakaoId(), paymentNo));
	}

	/**
	 * 결제 취소 (드로어의 [결제 취소]).
	 *
	 * <b>응답이 PROCESSING 이면 실패가 아니다.</b> 취소는 멱등하지 않아 다시 보내면
	 * 두 번 환불된다. 프론트는 재시도 버튼 대신 상세를 다시 불러야 한다.
	 */
	@Operation(summary = "셀러 결제 취소",
			description = """
					셀러가 이 주문을 취소한다. 금액은 서버가 계산하므로 보내지 않는다.

					★ <b>차수 한쪽만 취소하지 않는다.</b> 1차금 줄에서 눌러도 2차금까지 같이
					  되돌린다 — 한쪽만 돌려주면 받은 돈과 보낸 물건이 어긋난다.
					  장바구니 주문에서 폼 하나만 빼려면 본문의 orderId 를 채운다.
					★ 취소 구간은 구매자 취소와 같다. 공동구매는 발주 전, 단독 판매는 발송 전까지다 —
					  발주가 나간 뒤에는 셀러도 시스템으로 되돌릴 수 없다.
					★ 응답이 PROCESSING 이면 실패가 아니다. 재시도 버튼 대신 상세를 다시 부른다.
					★ 응답이 SETTLED_MANUAL 이면 정산이 끝나 자동 취소가 안 되는 건이다.
					  상세의 manualRefund 로 넘어가 계좌로 직접 이체한 뒤 완료로 바꾼다.
					★ 정산 시간대(23:30~00:30)에는 409 다. 그 시간대에는 애초에 버튼을 꺼 둔다.
					""")
	@PostMapping("/{paymentNo}/cancel")
	public OrderRefundResponse cancel(
			@LoginUser SessionUser user,

			@Parameter(description = "결제번호. 목록 줄에 찍힌 그 값이다", example = "ORD-260828-92-1")
			@PathVariable String paymentNo,

			@Valid @RequestBody OrderRefundRequest request) {

		return sellerPaymentService.cancel(user.kakaoId(), paymentNo, request.orderId(), request.reason());
	}

	/**
	 * 환불 완료로 변경 (S14).
	 *
	 * <b>처리한 뒤의 상세를 그대로 돌려준다</b> — 화면을 다시 그리는 데 쓴다.
	 * 서비스를 두 번 부르는 이유는 표시가 자기 트랜잭션에서 커밋된 뒤에 읽어야 하기
	 * 때문이다. 같은 트랜잭션 안에서 읽으면 방금 바꾼 값이 아직 안 보인다.
	 */
	@Operation(summary = "정산 후 환불 완료로 변경",
			description = """
					정산이 끝나 시스템으로 취소할 수 없던 건을, 셀러가 계좌로 직접 이체하고
					완료로 표시한다. 상세의 manualRefund 가 있고 completed 가 false 인 건만 대상이다.

					★ <b>출금이 아니다.</b> 서버는 돈을 보내지 않는다 — 셀러가 이미 보냈다는 표시다.
					  실제 이체 여부를 서버가 확인할 방법은 없다.
					★ 이 표시로 주문이 취소로 내려가고 재고가 돌아가며 구매자에게 환불 알림이 나간다.
					  시스템 취소와 뒷정리가 같다.
					★ 멱등하다. 두 번 눌러도 재고가 두 번 돌아가지 않는다.
					★ 응답은 처리 후의 상세다 — 조회 API 를 따로 부를 필요가 없다.
					""")
	@PostMapping("/{paymentNo}/manual-refund")
	public SellerPaymentDetailResponse completeManualRefund(
			@LoginUser SessionUser user,

			@Parameter(description = "결제번호. 목록 줄에 찍힌 그 값이다", example = "ORD-260828-92-1")
			@PathVariable String paymentNo) {

		sellerPaymentService.completeManualRefund(user.kakaoId(), paymentNo);
		return sellerPaymentService.detail(user.kakaoId(), paymentNo);
	}
}
