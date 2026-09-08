package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;

import java.util.List;

/**
 * 목표수량에 못 미친 채 마감된 공구를 정리한다 (D-026).
 *
 * 마감 배치({@code SaleFormCloseBatch})가 {@code SELLING → CLOSED} 로 넘긴 뒤,
 * 이 배치가 폼마다 {@code shortfall_policy} 를 적용한다.
 *
 * <pre>
 *   CANCEL   그 폼의 주문을 전부 취소한다 — 이미 받은 1차금·2차금을 돌려준다
 *   PROCEED  그대로 진행한다. 할 일이 없다
 *   EXTEND   ★ 아직 구현하지 않았다 (아래)
 * </pre>
 *
 * <b>{@code @Transactional} 이 없다.</b> 취소가 point3 를 부르기 때문이다 (CLAUDE.md 규칙 1).
 * DB 쓰기는 {@link ShortfallWriter} 의 짧은 트랜잭션으로 나간다.
 *
 * <b>두 번 돌면 안 된다.</b> 이미 나간 돈을 다시 돌려주는 일이다. 그래서 폼마다
 * {@code shortfall_done_at} 을 찍고, 조회 자체를 {@code FOR UPDATE SKIP LOCKED} 로 잠근다.
 * 취소가 미확정({@code PROCESSING})으로 끝나도 done 을 찍는다 — 그 건은 취소 대사 배치가
 * 조회 → resume 으로 끝낸다. 여기서 다시 보내면 이중 환불이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortfallCancelBatch {

	private static final int BATCH_SIZE = 20;
	private static final String REASON = "목표수량 미달로 공동구매가 취소되었습니다.";

	private final ShortfallWriter writer;
	private final OrderRefundService orderRefundService;

	@Scheduled(fixedDelayString = "${moeum.batch.shortfall-delay:60000}")
	public void run() {
		try {
			int handled = handleOnce();
			if (handled > 0) {
				log.info("목표수량 미달 처리: {}건", handled);
			}
		} catch (RuntimeException e) {
			// 배치가 죽으면 다음 주기가 오지 않는다. 한 번의 실패로 멈추지 않게 한다
			log.error("목표수량 미달 배치 실패", e);
		}
	}

	/** 한 바퀴. 처리한 폼 수를 돌려준다 — 테스트가 직접 부른다 */
	public int handleOnce() {
		List<ShortfallWriter.Claimed> forms = writer.claim(BATCH_SIZE);

		for (ShortfallWriter.Claimed form : forms) {
			handle(form);
			// 정책과 결과에 상관없이 찍는다. 다음 주기에 같은 폼을 또 집으면 이중 환불이다
			writer.markDone(form.saleFormId());
		}
		return forms.size();
	}

	private void handle(ShortfallWriter.Claimed form) {
		if (!form.shortfall()) {
			return;
		}
		switch (form.policy()) {
			case CANCEL -> cancelAll(form);
			case EXTEND -> log.warn(
					// 몇 번까지 · 얼마나 미룰지가 기획 미확정이다 (domain.md). 임의로 정하지 않는다
					"목표수량 미달인데 EXTEND 정책이다 — 연장 규칙이 아직 없어 수동 처리가 필요하다: "
							+ "saleFormId={}, sold={}, target={}",
					form.saleFormId(), form.sold(), form.targetQty());
			case PROCEED -> log.info("목표수량 미달이지만 그대로 진행한다: saleFormId={}, sold={}, target={}",
					form.saleFormId(), form.sold(), form.targetQty());
		}
	}

	private void cancelAll(ShortfallWriter.Claimed form) {
		List<Long> orderIds = writer.cancelableOrderIds(form.saleFormId());
		log.info("목표수량 미달로 취소한다: saleFormId={}, sold={}, target={}, 주문 {}건",
				form.saleFormId(), form.sold(), form.targetQty(), orderIds.size());

		for (Long orderId : orderIds) {
			cancelOne(form.saleFormId(), orderId);
		}
	}

	/**
	 * 주문 하나를 취소한다.
	 *
	 * <b>한 건이 터져도 나머지는 계속한다.</b> 앞에서 멈추면 뒤의 구매자들은 돈을 돌려받지 못한 채
	 * 다음 주기를 기다리는데, 그 주기는 {@code shortfall_done_at} 때문에 오지 않는다.
	 */
	private void cancelOne(Long saleFormId, Long orderId) {
		try {
			orderRefundService.cancelByOrder(orderId, REASON).ifPresent(result -> {
				if (result.status() != OrderRefundResponse.Status.COMPLETED) {
					// PROCESSING 은 대사 배치가 끝낸다. FAILED · SETTLED_MANUAL 은 사람이 봐야 한다
					log.warn("미달 취소가 완료되지 않았다: saleFormId={}, orderId={}, status={}",
							saleFormId, orderId, result.status());
				}
			});
		} catch (RuntimeException e) {
			log.error("미달 취소 실패: saleFormId={}, orderId={}", saleFormId, orderId, e);
		}
	}
}
