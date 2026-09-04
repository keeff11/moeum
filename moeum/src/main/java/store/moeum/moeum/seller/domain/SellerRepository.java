package store.moeum.moeum.seller.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

	Optional<Seller> findByKakaoId(String kakaoId);

	boolean existsByKakaoId(String kakaoId);

	boolean existsByStoreSlug(String storeSlug);
}
