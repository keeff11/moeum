package store.moeum.moeum.saleform.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SaleFormHistoryRepository extends JpaRepository<SaleFormHistory, Long> {

	List<SaleFormHistory> findBySaleFormIdOrderByIdDesc(Long saleFormId);
}
