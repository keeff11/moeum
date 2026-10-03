package store.moeum.moeum.payment.refund;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.alert.AlertSender;
import store.moeum.moeum.payment.refund.dto.OrderRefundResponse;

import java.util.ArrayList;
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
 *
 * <b>사람이 손대야 하는 건은 Slack 으로 알린다 (D-069).</b> 폼마다 한 번만 돌아서 다시 볼 기회가 없다.
 * <pre>
 *   취소가 FAILED · SETTLED_MANUAL · 예외   → 폼마다 한 번에 모아 CRITICAL
 *   EXTEND                                  → CRITICAL (연장 규칙이 없어 아무것도 하지 않았다)
 *   PROCESSING                              → 알리지 않는다. 취소 대사 알림이 맡는다
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortfallCancelBatch {

	private static final int BATCH_SIZE = 20;
	private static final String REASON = "목표수량 미달로 공동구매가 취소되었습니다.";

	private final ShortfallWriter writer;
	private final OrderRefundService orderRefundService;
	private final AlertSender alertSender;

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

	/**
	 * 폼 하나를 처리하고 결과를 구매자에게 알린다 (알림톡 6번 · D-050).
	 *
	 * <b>알림은 구매자 기준으로 둘이다.</b> 목표 달성 여부가 아니라 "물건을 받는가" 로
	 * 가른다 — 미달이어도 PROCEED 면 받으므로 성사와 같은 쪽이다. 받을 수 없는 것은
	 * CANCEL 하나뿐이다.
	 *
	 * EXTEND 는 알리지 않는다. 연장 규칙이 기획 미확정이라 처리 자체를 안 하는데,
	 * 결과가 안 정해진 상태에서 "연장됐다" 고 알릴 수는 없다.
	 */
	private void handle(ShortfallWriter.Claimed form) {
		if (!form.shortfall()) {
			notify(form, OutboxEventType.RECRUITMENT_SUCCEEDED, false);
			return;
		}
		switch (form.policy()) {
			case CANCEL -> {
				cancelAll(form);
				notify(form, OutboxEventType.RECRUITMENT_FAILED, true);
			}
			case EXTEND -> {
				// 몇 번까지 · 얼마나 미룰지가 기획 미확정이다 (domain.md). 임의로 정하지 않는다
				log.warn("목표수량 미달인데 EXTEND 정책이다 — 연장 규칙이 아직 없어 수동 처리가 필요하다: "
								+ "saleFormId={}, sold={}, target={}",
						form.saleFormId(), form.sold(), form.targetQty());
				alertSender.send(AlertLevel.CRITICAL, """
						*[목표수량 미달] EXTEND 폼 — 수동 처리 필요*
						saleFormId=%d · 판매 %d / 목표 %d
						연장 규칙이 아직 없어 아무것도 하지 않았다. 폼은 CLOSED 이고 구매자에게 알리지 않았다.
						셀러와 연장 · 진행 · 취소 중 하나를 정해 직접 처리한다.""".formatted(
						form.saleFormId(), form.sold(), form.targetQty()));
			}
			case PROCEED -> {
				log.info("목표수량 미달이지만 그대로 진행한다: saleFormId={}, sold={}, target={}",
						form.saleFormId(), form.sold(), form.targetQty());
				notify(form, OutboxEventType.RECRUITMENT_SUCCEEDED, true);
			}
		}
	}

	/**
	 * <b>알림이 터져도 처리는 끝난 것으로 둔다.</b> 여기서 예외가 올라가면
	 * {@code markDone} 이 안 찍히고, 다음 주기가 같은 폼을 다시 집어 <b>이미 환불한 주문을
	 * 또 취소한다.</b> 알림 한 건보다 이중 환불이 훨씬 나쁘다.
	 */
	private void notify(ShortfallWriter.Claimed form, OutboxEventType eventType, boolean shortfall) {
		try {
			int recorded = writer.notifyRecruitment(form.saleFormId(), eventType, shortfall);
			log.info("모집 결과 알림 적재: saleFormId={}, type={}, {}건",
					form.saleFormId(), eventType, recorded);
		} catch (RuntimeException e) {
			log.error("모집 결과 알림 적재 실패(처리는 계속한다): saleFormId={}, type={}",
					form.saleFormId(), eventType, e);
		}
	}

	private void cancelAll(ShortfallWriter.Claimed form) {
		List<Long> orderIds = writer.cancelableOrderIds(form.saleFormId());
		log.info("목표수량 미달로 취소한다: saleFormId={}, sold={}, target={}, 주문 {}건",
				form.saleFormId(), form.sold(), form.targetQty(), orderIds.size());

		List<String> problems = new ArrayList<>();
		for (Long orderId : orderIds) {
			String problem = cancelOne(form.saleFormId(), orderId);
			if (problem != null) {
				problems.add("orderId=" + orderId + " " + problem);
			}
		}
		if (!problems.isEmpty()) {
			alertSender.send(AlertLevel.CRITICAL, """
					*[목표수량 미달] 자동 취소 %d건 실패*
					saleFormId=%d · 판매 %d / 목표 %d · 취소 대상 %d건
					%s
					이 폼은 다시 돌지 않는다. 건마다 상태를 확인하고 직접 환불한다.""".formatted(
					problems.size(), form.saleFormId(), form.sold(), form.targetQty(), orderIds.size(),
					String.join("\n", problems)));
		}
	}

	/**
	 * 주문 하나를 취소한다.
	 *
	 * <b>한 건이 터져도 나머지는 계속한다.</b> 앞에서 멈추면 뒤의 구매자들은 돈을 돌려받지 못한 채
	 * 다음 주기를 기다리는데, 그 주기는 {@code shortfall_done_at} 때문에 오지 않는다.
	 *
	 * @return 사람이 봐야 하면 그 사유. 아니면 null
	 */
	private String cancelOne(Long saleFormId, Long orderId) {
		try {
			OrderRefundResponse.Status status = orderRefundService.cancelByOrder(orderId, REASON)
					.map(OrderRefundResponse::status)
					.orElse(null);
			if (status == null || status == OrderRefundResponse.Status.COMPLETED) {
				return null;
			}
			log.warn("미달 취소가 완료되지 않았다: saleFormId={}, orderId={}, status={}",
					saleFormId, orderId, status);
			// PROCESSING 은 대사 배치가 끝낸다. 오래 남으면 취소 대사 알림이 울린다
			return status == OrderRefundResponse.Status.PROCESSING ? null : status.name();
		} catch (RuntimeException e) {
			log.error("미달 취소 실패: saleFormId={}, orderId={}", saleFormId, orderId, e);
			return e.getClass().getSimpleName();
		}
	}
}
