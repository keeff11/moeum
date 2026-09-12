package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.SecondCharge;
import store.moeum.moeum.order.domain.SecondChargeRepository;
import store.moeum.moeum.outbox.OutboxRecorder;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 2차금 미납 자동 독촉 (알림톡 8번 · DUE-110 · D-050).
 *
 * <b>셀러가 S10 을 눌러 주기를 기다릴 수 없다.</b> 잔금을 안 내면 셀러는 물건을 보낼 수
 * 없고 구매자는 왜 안 오는지 모른다. 셀러가 잊으면 그대로 멈춘다 —
 * payment-flow 표의 "1일 주기 자동 배치" 가 이 자리다.
 *
 * <b>셀러의 수동 청구와 같은 이력을 쓴다.</b> {@code second_charge} 한 표에 누가 보냈든
 * 한 행이 쌓이고, 쿨다운도 그 표를 본다. 표를 나누면 배치가 독촉한 직후에 셀러가 또
 * 보내게 되고, <b>구매자는 같은 독촉을 하루에 두 번 받는다.</b>
 *
 * <b>쿨다운이 수동보다 길다.</b> 셀러가 직접 누르는 것은 하루 한 번까지 허용하지만
 * (그 판단은 셀러가 한다), 자동 독촉이 매일 가면 알림톡 단가가 쌓이고 구매자도 지친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecondPaymentReminderBatch {

	/** 한 바퀴에 처리할 묶음 수. 남은 것은 다음 주기가 집는다 */
	private static final int BATCH_SIZE = 100;

	private final OrderGroupRepository orderGroupRepository;
	private final SecondChargeRepository secondChargeRepository;
	private final OutboxRecorder outboxRecorder;
	private final Clock clock;

	/**
	 * 하루 한 번, 트래픽이 없는 새벽에 돈다.
	 *
	 * 테스트에서는 {@code "-"} 로 꺼 둔다 — 알림을 쌓는 배치라 테스트가 직접 부른다.
	 */
	@Scheduled(cron = "${moeum.batch.second-reminder-cron:0 30 9 * * *}", zone = "Asia/Seoul")
	public void run() {
		try {
			remindOnce();
		} catch (RuntimeException e) {
			// 배치가 죽으면 다음 주기가 오지 않는다. 한 번의 실패로 멈추지 않게 한다
			log.error("2차금 독촉 배치 실패", e);
		}
	}

	/** 한 바퀴. 독촉한 묶음 수를 돌려준다 — 테스트가 직접 부른다 */
	@Transactional
	public int remindOnce() {
		List<OrderGroup> targets =
				orderGroupRepository.findSecondUnpaid(PageRequest.of(0, BATCH_SIZE));
		if (targets.isEmpty()) {
			return 0;
		}

		Map<Long, LocalDateTime> lastCharged = secondChargeRepository.findLastChargedAt(
				targets.stream().map(OrderGroup::getId).toList());
		LocalDateTime now = LocalDateTime.now(clock);

		int reminded = 0;
		for (OrderGroup group : targets) {
			if (inCooldown(lastCharged.get(group.getId()), now)) {
				continue;
			}
			record(group);
			// 셀러의 수동 청구와 같은 표다. 이 행이 곧 다음 쿨다운의 기준이 된다
			secondChargeRepository.save(SecondCharge.of(group, now));
			reminded++;
		}

		if (reminded > 0) {
			log.info("2차금 자동 독촉: 대상 {}건 중 {}건", targets.size(), reminded);
		}
		return reminded;
	}

	/**
	 * 독촉을 적재한다.
	 *
	 * <b>청구 알림과 다른 타입이다.</b> {@code SECOND_PAYMENT_DUE} 는 "이제 낼 수 있다" 고,
	 * 이쪽은 "아직 안 냈다" 다 — 문구가 다르니 템플릿도 갈린다.
	 */
	private void record(OrderGroup group) {
		outboxRecorder.record(OutboxAggregate.ORDER_GROUP, group.getId(),
				OutboxEventType.SECOND_PAYMENT_OVERDUE,
				Map.of(
						"orderToken", group.getOrderToken(),
						"buyerId", group.getBuyer().getId(),
						"amount", group.secondPaymentAmount()));
	}

	private boolean inCooldown(LocalDateTime lastChargedAt, LocalDateTime now) {
		return lastChargedAt != null && lastChargedAt.isAfter(now.minus(cooldown()));
	}

	/** 자동 독촉 간격. 매일 보내면 단가가 쌓이고 구매자도 지친다 */
	private Duration cooldown() {
		return Duration.ofDays(3);
	}
}
