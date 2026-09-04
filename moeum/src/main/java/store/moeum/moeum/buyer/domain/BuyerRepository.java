package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BuyerRepository extends JpaRepository<Buyer, Long> {

	Optional<Buyer> findByKakaoId(String kakaoId);
}
