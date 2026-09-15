package store.moeum.moeum.buyer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.buyer.dto.RefundAccountRequest;
import store.moeum.moeum.buyer.dto.RefundAccountResponse;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

/**
 * 환불 계좌 (D-057). B5 1차금 결제 화면에서 배송지와 함께 받는다.
 *
 * 배송지와 따로 둔 것은 성격이 달라서다 — 배송지는 주문 시점 값이 굳지만(shipping 스냅샷),
 * 환불 계좌는 늘 최신 한 벌만 있으면 된다. 돈은 환불하는 그 시점에 보낸다.
 */
@Tag(name = "구매자", description = "배송지 · 환불 계좌")
@RestController
@RequestMapping("/me/refund-account")
@RequiredArgsConstructor
public class BuyerRefundAccountController {

	private final RefundAccountService refundAccountService;

	/** 등록 전이면 204. 프론트가 빈 폼을 그린다 */
	@Operation(summary = "환불 계좌 조회",
			description = """
					등록한 환불 계좌가 없으면 본문 없이 204 다. 프론트는 빈 폼을 그리면 된다.

					★ 계좌번호는 뒤 네 자리만 내려간다(`accountNoMasked`). 수정 화면에서는
					전체 번호를 다시 입력받아야 한다 — 서버는 전체 번호를 응답에 담지 않는다.
					""")
	@GetMapping
	public ResponseEntity<RefundAccountResponse> get(@LoginUser SessionUser user) {
		return refundAccountService.find(user)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(summary = "환불 계좌 등록 · 수정",
			description = """
					환불 계좌는 하나만 갖는다. 계좌부가 아니라 덮어쓰기다.

					★ <b>1차금 결제(`/pay`)의 선행 조건이다.</b> 등록하지 않으면 400
					(`REFUND_ACCOUNT_REQUIRED`) 으로 막힌다. <b>공동구매·단독(재고) 판매를
					가리지 않는다.</b>

					계좌번호는 하이픈이 있어도 되고, 서버가 숫자만 남겨 암호화해 저장한다.
					""")
	@PutMapping
	public RefundAccountResponse put(@LoginUser SessionUser user,
	                                 @Valid @RequestBody RefundAccountRequest request) {
		return refundAccountService.save(user, request);
	}
}
