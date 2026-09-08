package store.moeum.moeum.seller.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

	Optional<Seller> findByKakaoId(String kakaoId);

	boolean existsByKakaoId(String kakaoId);

	boolean existsByStoreSlug(String storeSlug);

	/** 셀러 페이지(B0) 진입. store_slug 가 곧 공개 주소다 */
	Optional<Seller> findByStoreSlug(String storeSlug);
}
