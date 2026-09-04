package store.moeum.moeum.cart.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

	Optional<Cart> findByBuyerIdAndSellerId(Long buyerId, Long sellerId);

	@Query("select c from Cart c join fetch c.seller where c.buyer.id = :buyerId order by c.id desc")
	List<Cart> findAllByBuyerId(Long buyerId);

	@Query("select c from Cart c left join fetch c.items where c.id = :cartId")
	Optional<Cart> findDetailById(Long cartId);
}
