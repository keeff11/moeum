package store.moeum.moeum.buyer.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BuyerRepository extends JpaRepository<Buyer, Long> {

	Optional<Buyer> findByKakaoId(String kakaoId);

	/**
	 * 문자 인증을 한 구매자 안에서 줄 세운다 (D-064).
	 * 동시에 눌러도 발송 간격이 지켜지고, 동시에 틀려도 시도 횟수가 빠짐없이 오른다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Buyer b where b.id = :id")
	Optional<Buyer> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 결제자 식별값을 저장하기 전에 잡는 잠금 (D-084). <b>기다리지 않는다.</b>
	 * 같은 구매자의 다른 탭 주문이 회원 행을 쥐고 있으면 바로 실패한다 —
	 * 결제는 이미 확정됐으니 결제 응답을 붙잡으면서까지 저장할 값이 아니다.
	 */
	@Query(value = "SELECT id FROM buyer WHERE id = :id FOR UPDATE NOWAIT", nativeQuery = true)
	Optional<Long> lockNowait(@Param("id") Long id);
}
