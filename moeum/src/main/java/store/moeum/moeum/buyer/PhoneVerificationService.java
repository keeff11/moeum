package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.dto.NotifyPhoneResponse;
import store.moeum.moeum.buyer.dto.PhoneVerificationConfirmRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.outbox.SmsSender;
import store.moeum.moeum.outbox.infra.SolapiFailedException;
import store.moeum.moeum.outbox.infra.SolapiUncertainException;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * 알림 받을 번호 문자 인증 (D-064).
 *
 * 알림톡은 인증을 마친 이 번호로 간다. 인증 전이면 예전처럼 배송지 번호로 간다 (D-040).
 *
 * <pre>
 *   발송   발급 기록 커밋 → (트랜잭션 밖) 문자
 *            4xx          → 기록을 지우고 SMS_SEND_FAILED    바로 다시 요청할 수 있다
 *            5xx · 타임아웃 → 기록을 두고 SMS_SEND_UNCERTAIN  실제로 도착했을 수 있다
 *   확인   구매자 잠금 → 맞춰 보기 → 커밋 → (틀렸으면) 예외
 * </pre>
 *
 * 결과 불명에서 기록을 남기는 것은 결제와 같은 판단이다 (CLAUDE.md 규칙 3) —
 * 문자가 늦게라도 도착하면 그 번호로 인증할 수 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationService {

	/** 한글은 2바이트다. 90바이트를 넘기면 장문(LMS)이라 단가가 오른다 — 지금 38바이트 */
	private static final String TEXT = "[모음] 인증번호 %s (3분 안에 입력)";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PhoneVerificationWriter writer;
	private final BuyerService buyerService;
	private final SmsSender smsSender;

	/** 인증 전이면 빈 결과다. 조회 때문에 계정을 만들지는 않는다 */
	@Transactional(readOnly = true)
	public Optional<NotifyPhoneResponse> find(SessionUser user) {
		return buyerService.findByKakaoId(user.kakaoId())
				.filter(buyer -> buyer.hasNotifyPhone())
				.map(NotifyPhoneResponse::from);
	}

	public PhoneVerificationResponse send(SessionUser user, PhoneVerificationRequest request) {
		String phone = digitsOf(request.phone());
		String code = newCode();

		PhoneVerificationWriter.Issued issued = writer.issue(user, phone, code);

		try {
			smsSender.send(phone, TEXT.formatted(code));

		} catch (SolapiFailedException e) {
			writer.discard(issued.id());
			log.warn("[문자/인증] 발송 확정 실패: verificationId={} {}", issued.id(), e.getMessage());
			throw new BusinessException(ErrorCode.SMS_SEND_FAILED);

		} catch (SolapiUncertainException e) {
			log.warn("[문자/인증] 발송 결과 불명 — 기록을 남긴다: verificationId={} {}", issued.id(), e.getMessage());
			throw new BusinessException(ErrorCode.SMS_SEND_UNCERTAIN);
		}

		// 번호와 인증번호는 남기지 않는다 (CLAUDE.md 규칙 10 과 같은 판단)
		log.info("[문자/인증] 발송: verificationId={}", issued.id());
		return new PhoneVerificationResponse(issued.expiresAt(), issued.resendAvailableAt());
	}

	public NotifyPhoneResponse confirm(SessionUser user, PhoneVerificationConfirmRequest request) {
		PhoneVerificationWriter.Confirmed result =
				writer.confirm(user, digitsOf(request.phone()), request.code());

		return switch (result.outcome()) {
			case VERIFIED -> NotifyPhoneResponse.from(result.buyer());
			case NOT_FOUND -> throw new BusinessException(ErrorCode.PHONE_VERIFICATION_NOT_FOUND);
			case EXPIRED -> throw new BusinessException(ErrorCode.PHONE_VERIFICATION_EXPIRED);
			case LOCKED -> throw new BusinessException(ErrorCode.PHONE_VERIFICATION_LOCKED);
			case MISMATCH -> throw mismatch(result.remainingAttempts());
		};
	}

	/** 남은 기회를 알려 준다. 다 쓰면 다시 받으라고 한다 */
	private static BusinessException mismatch(int remaining) {
		if (remaining <= 0) {
			return new BusinessException(ErrorCode.PHONE_VERIFICATION_LOCKED);
		}
		return new BusinessException(ErrorCode.PHONE_VERIFICATION_MISMATCH,
				ErrorCode.PHONE_VERIFICATION_MISMATCH.message() + " (남은 기회 " + remaining + "번)");
	}

	/** 000000 부터 999999 까지. 앞자리 0 을 살린다 */
	private static String newCode() {
		return String.format("%06d", RANDOM.nextInt(1_000_000));
	}

	private static String digitsOf(String phone) {
		return phone.replaceAll("[^0-9]", "");
	}
}
