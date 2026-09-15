package store.moeum.moeum.buyer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BuyerRefundAccountRepository extends JpaRepository<BuyerRefundAccount, Long> {

	Optional<BuyerRefundAccount> findByBuyerId(Long buyerId);

	/** 결제 시작 전 등록 여부만 확인한다 — 계좌번호를 복호화할 이유가 없다 */
	boolean existsByBuyerId(Long buyerId);
}
