package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BuyerAddressRepository extends JpaRepository<BuyerAddress, Long> {

	Optional<BuyerAddress> findByBuyerId(Long buyerId);
}
