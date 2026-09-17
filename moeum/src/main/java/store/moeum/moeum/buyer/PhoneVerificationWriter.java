package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.buyer.domain.PhoneVerification;
import store.moeum.moeum.buyer.domain.PhoneVerificationRepository;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 문자 인증의 DB 구간 (D-064).
 *
 * <b>문자 발송과 나눠 둔 이유는 트랜잭션 경계다</b> (CLAUDE.md 규칙 1).
 * 발급 기록을 먼저 커밋하고, 문자는 {@link PhoneVerificationService} 가 트랜잭션 밖에서 보낸다.
 * 순서가 반대면 문자는 나갔는데 기록이 없어 그 번호를 아무도 인증할 수 없다.
 *
 * 한 구매자의 발급 · 확인은 구매자 행을 잠가 줄 세운다. 동시에 눌러도 발송 간격이
 * 지켜지고, 동시에 틀려도 시도 횟수가 빠짐없이 오른다.
 */
@Component
@RequiredArgsConstructor
public class PhoneVerificationWriter {

	/** 인증번호 유효 시간 */
	static final Duration TTL = Duration.ofMinutes(3);

	/** 다시 받기까지 기다리는 시간 */
	static final Duration RESEND_INTERVAL = Duration.ofMinutes(1);

	/**
	 * 하루 발송 한도. 구매자 기준과 번호 기준을 따로 센다 —
	 * 카카오 계정을 여럿 만들어 한 번호에 문자를 쏟아붓는 것도 막는다.
	 */
	static final int DAILY_LIMIT = 5;

	private final BuyerService buyerService;
	private final BuyerRepository buyerRepository;
	private final PhoneVerificationRepository verificationRepository;
	private final Clock clock;

	public record Issued(Long id, LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {
	}

	public enum Outcome { VERIFIED, NOT_FOUND, EXPIRED, LOCKED, MISMATCH }

	public record Confirmed(Outcome outcome, int remainingAttempts, Buyer buyer) {
	}

	/**
	 * 발급 기록을 남긴다. 발송 간격이나 하루 한도에 걸리면 기록 없이 막는다.
	 *
	 * 한도는 발급한 건수로 센다. 발송에 확정 실패한 건은 {@link #discard} 로 지워
	 * 세지 않는다 — 우리 쪽 문제로 구매자의 기회가 줄면 안 된다.
	 */
	@Transactional
	public Issued issue(SessionUser user, String phone, String code) {
		// 인증은 배송지보다 먼저 올 수 있다. 여기서 처음 계정이 만들어질 수 있다 (D-015)
		Buyer buyer = lock(buyerService.findOrCreate(user).getId());
		LocalDateTime now = LocalDateTime.now(clock);

		verificationRepository.findFirstByBuyerIdOrderByIdDesc(buyer.getId())
				.filter(latest -> now.isBefore(latest.getCreatedAt().plus(RESEND_INTERVAL)))
				.ifPresent(latest -> {
					throw new BusinessException(ErrorCode.PHONE_VERIFICATION_TOO_SOON);
				});

		LocalDateTime startOfDay = LocalDate.now(clock).atStartOfDay();

		if (verificationRepository.countByBuyerIdAndCreatedAtGreaterThanEqual(buyer.getId(), startOfDay) >= DAILY_LIMIT
				|| verificationRepository.countByPhoneAndCreatedAtGreaterThanEqual(phone, startOfDay) >= DAILY_LIMIT) {
			throw new BusinessException(ErrorCode.PHONE_VERIFICATION_DAILY_LIMIT);
		}

		PhoneVerification saved = verificationRepository.save(
				PhoneVerification.issue(buyer.getId(), phone, code, now, now.plus(TTL)));

		return new Issued(saved.getId(), saved.getExpiresAt(), now.plus(RESEND_INTERVAL));
	}

	/** 발송이 확정 실패한 기록을 지운다. 다시 요청할 때 발송 간격에 걸리지 않게 한다 */
	@Transactional
	public void discard(Long verificationId) {
		verificationRepository.deleteById(verificationId);
	}

	/**
	 * 인증번호를 맞춰 본다.
	 *
	 * <b>틀려도 예외를 던지지 않고 결과로 돌려준다.</b> 여기서 던지면 트랜잭션이 되돌아가
	 * 시도 횟수가 오르지 않는다 — 몇 번이고 찍어 볼 수 있게 된다. 예외는 커밋 뒤에
	 * {@link PhoneVerificationService} 가 던진다.
	 */
	@Transactional
	public Confirmed confirm(SessionUser user, String phone, String code) {
		Buyer found = buyerService.findByKakaoId(user.kakaoId()).orElse(null);

		if (found == null) {
			return new Confirmed(Outcome.NOT_FOUND, 0, null);
		}
		Buyer buyer = lock(found.getId());
		LocalDateTime now = LocalDateTime.now(clock);

		PhoneVerification latest = verificationRepository.findFirstByBuyerIdOrderByIdDesc(buyer.getId())
				// 요청한 번호와 다른 번호로 확인하려 하면 받은 적이 없는 것으로 친다.
				// 남의 번호로 받은 인증번호로 내 번호를 인증하는 길을 막는다
				.filter(v -> v.getPhone().equals(phone))
				// 이미 쓴 번호는 다시 쓰지 않는다
				.filter(v -> !v.isVerified())
				.orElse(null);

		if (latest == null) {
			return new Confirmed(Outcome.NOT_FOUND, 0, buyer);
		}
		if (latest.isExpired(now)) {
			return new Confirmed(Outcome.EXPIRED, 0, buyer);
		}
		if (latest.isExhausted()) {
			return new Confirmed(Outcome.LOCKED, 0, buyer);
		}
		if (!latest.tryMatch(code, now)) {
			return new Confirmed(Outcome.MISMATCH, latest.remainingAttempts(), buyer);
		}

		buyer.verifyNotifyPhone(phone, now);
		return new Confirmed(Outcome.VERIFIED, latest.remainingAttempts(), buyer);
	}

	private Buyer lock(Long buyerId) {
		return buyerRepository.findByIdForUpdate(buyerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.BUYER_NOT_FOUND));
	}
}
