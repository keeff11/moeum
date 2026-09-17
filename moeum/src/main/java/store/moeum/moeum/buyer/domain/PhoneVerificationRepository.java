package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

	/**
	 * 이 구매자가 마지막으로 받은 인증번호.
	 *
	 * <b>유효한 것은 마지막 한 건뿐이다.</b> 다시 받으면 앞의 번호는 쓸 수 없다 —
	 * 살아 있는 번호가 여럿이면 찍어 맞힐 기회도 그만큼 늘어난다.
	 * 동시성은 호출하는 쪽이 구매자 행을 잠가서 막는다.
	 */
	Optional<PhoneVerification> findFirstByBuyerIdOrderByIdDesc(Long buyerId);

	long countByBuyerIdAndCreatedAtGreaterThanEqual(Long buyerId, LocalDateTime since);

	long countByPhoneAndCreatedAtGreaterThanEqual(String phone, LocalDateTime since);
}
