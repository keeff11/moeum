package store.moeum.moeum.payment.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

	List<PaymentEvent> findByPaymentIdOrderByIdAsc(Long paymentId);
}
