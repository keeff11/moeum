package store.moeum.moeum.saleform.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

	/** 옵션 → 상품 → 판매 폼 → 셀러까지 한 번에. 장바구니 담기·주문 생성에서 매번 필요하다 */
	@Query("""
			select o from ProductOption o
			  join fetch o.product p
			  join fetch p.saleForm f
			  join fetch f.seller
			 where o.id = :optionId
			""")
	Optional<ProductOption> findWithFormById(Long optionId);

	@Query("""
			select o from ProductOption o
			  join fetch o.product p
			  join fetch p.saleForm f
			  join fetch f.seller
			 where o.id in :optionIds
			""")
	List<ProductOption> findAllWithFormByIdIn(List<Long> optionIds);
}
