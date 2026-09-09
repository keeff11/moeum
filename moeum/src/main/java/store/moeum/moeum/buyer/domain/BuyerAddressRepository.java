package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BuyerAddressRepository extends JpaRepository<BuyerAddress, Long> {

	Optional<BuyerAddress> findByBuyerId(Long buyerId);

	/** 결제 시작 전 배송지 유무만 확인한다 — 주소 전체를 끌어올 이유가 없다 */
	boolean existsByBuyerId(Long buyerId);
}
