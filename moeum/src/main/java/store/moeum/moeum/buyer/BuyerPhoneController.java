package store.moeum.moeum.buyer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.buyer.dto.NotifyPhoneResponse;
import store.moeum.moeum.buyer.dto.PhoneVerificationConfirmRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationResponse;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

/**
 * 알림 받을 번호 (D-064). 문자 인증을 마친 번호로 주문 알림톡이 간다.
 *
 * 배송지 번호(수령인)와 다른 값이다 — 선물 주문이면 둘이 다르고, 알림은 구매자에게 가야 한다.
 */
@Tag(name = "구매자", description = "배송지 · 환불 계좌 · 알림 받을 번호")
@RestController
@RequestMapping("/me/phone")
@RequiredArgsConstructor
public class BuyerPhoneController {

	private final PhoneVerificationService phoneVerificationService;

	@Operation(summary = "알림 받을 번호 조회",
			description = """
					인증을 마친 번호가 없으면 본문 없이 204 다. 그때 알림톡은 배송지 번호로 간다.

					★ 번호는 가운데를 가린 값만 내려간다(`phoneMasked`). 바꿀 때는 새 번호로 다시 인증한다.
					""")
	@GetMapping
	public ResponseEntity<NotifyPhoneResponse> get(@LoginUser SessionUser user) {
		return phoneVerificationService.find(user)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@Operation(summary = "인증번호 받기",
			description = """
					입력한 번호로 6자리 인증번호 문자를 보낸다. 3분 동안 유효하다.

					- 다시 받으면 **앞의 인증번호는 쓸 수 없다.** 마지막 것만 유효하다
					- 1분에 한 번 (`429 PHONE_VERIFICATION_TOO_SOON`) · 하루 5번
					  (`429 PHONE_VERIFICATION_DAILY_LIMIT`, 구매자 기준과 번호 기준 각각) 까지
					- `502 SMS_SEND_FAILED` — 보내지 못했다. 번호를 고쳐 바로 다시 요청할 수 있다
					- `503 SMS_SEND_UNCERTAIN` — 늦게 도착할 수 있다. 받은 인증번호는 그대로 쓸 수 있고,
					  안 오면 1분 뒤 다시 요청한다

					응답의 `expiresAt` · `resendAvailableAt` 으로 화면의 타이머를 그린다.
					""")
	@PostMapping("/verification")
	public PhoneVerificationResponse send(@LoginUser SessionUser user,
	                                      @Valid @RequestBody PhoneVerificationRequest request) {
		return phoneVerificationService.send(user, request);
	}

	@Operation(summary = "인증번호 확인",
			description = """
					맞으면 이 번호가 알림 받을 번호로 저장된다.

					- `phone` 은 인증번호를 요청한 번호와 같아야 한다. 다르면 `400 PHONE_VERIFICATION_NOT_FOUND`
					- 틀리면 `400 PHONE_VERIFICATION_MISMATCH`. 메시지에 남은 기회가 실린다
					- 5번 틀리면 `400 PHONE_VERIFICATION_LOCKED` — 새 인증번호를 받아야 한다
					- 3분이 지나면 `400 PHONE_VERIFICATION_EXPIRED`
					""")
	@PostMapping("/verification/confirm")
	public NotifyPhoneResponse confirm(@LoginUser SessionUser user,
	                                   @Valid @RequestBody PhoneVerificationConfirmRequest request) {
		return phoneVerificationService.confirm(user, request);
	}
}
