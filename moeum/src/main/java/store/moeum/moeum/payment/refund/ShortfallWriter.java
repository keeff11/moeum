package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.ShortfallPolicy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 목표수량 미달 처리의 DB 접근. <b>point3 호출은 한 줄도 들어오지 않는다</b> (CLAUDE.md 규칙 1).
 *
 * {@link ShortfallCancelBatch} 가 오케스트레이션하고 여기는 짧은 트랜잭션으로 끊어 담는다.
 * 자기호출이면 프록시를 타지 않아 {@code FOR UPDATE SKIP LOCKED} 가 걸리지 않으므로 빈을 나눈다.
 */
@Component
@RequiredArgsConstructor
public class ShortfallWriter {

	private final SaleFormRepository saleFormRepository;
	private final OrderRepository orderRepository;
	private final Clock clock;

	/**
	 * 이번에 처리할 폼을 집는다.
	 *
	 * 엔티티가 아니라 값으로 빼내는 이유는 트랜잭션이 여기서 끝나기 때문이다 —
	 * 취소는 트랜잭션 밖에서 돌아야 하고, 준영속 엔티티를 들고 나가면 지연 로딩에서 터진다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<Claimed> claim(int limit) {
		return saleFormRepository.findClosedForShortfall(limit).stream()
				.map(ShortfallWriter::snapshot)
				.toList();
	}

	/** 이 폼은 훑었다. 정책과 결과에 상관없이 찍는다 — 두 번 돌면 이중 환불이다 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markDone(Long saleFormId) {
		saleFormRepository.findById(saleFormId)
				.ifPresent(form -> form.markShortfallDone(LocalDateTime.now(clock)));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public List<Long> cancelableOrderIds(Long saleFormId) {
		return orderRepository.findCancelableIdsBySaleForm(saleFormId);
	}

	private static Claimed snapshot(SaleForm form) {
		return new Claimed(form.getId(), form.isShortfall(),
				form.getShortfallPolicy() == null ? ShortfallPolicy.PROCEED : form.getShortfallPolicy(),
				form.getSold(), form.getTargetQty());
	}

	/**
	 * 폼 하나의 판정 결과.
	 *
	 * @param shortfall 목표수량에 못 미쳤는가. false 면 아무것도 하지 않고 done 만 찍는다
	 * @param policy    미설정이면 {@code PROCEED} 로 본다 — 정책이 없다고 남의 돈을 돌려주지 않는다
	 */
	public record Claimed(Long saleFormId, boolean shortfall, ShortfallPolicy policy,
	                      int sold, Integer targetQty) {
	}
}
