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
}
