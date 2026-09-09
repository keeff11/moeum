package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.SecondCharge;
import store.moeum.moeum.order.domain.SecondChargeRepository;
import store.moeum.moeum.order.domain.SellerOrderTab;
import store.moeum.moeum.order.dto.SecondChargeResponse;
import store.moeum.moeum.outbox.OutboxRecorder;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 2차금 일괄 청구 (와이어프레임 S10).
 *
 * <b>청구는 알림을 다시 내보내는 것이고 주문 상태를 바꾸지 않는다</b> (D-035).
 * 상태를 {@code SECOND_PENDING} 으로 올리면 {@code isSecondPaymentDue()} 가 false 가 되어
 * 정작 구매자의 결제 준비가 막힌다 — 청구하려다 결제를 막는 꼴이 된다.
 *
 * 대상 집합은 셀러 주문 목록의 '2차금 미납' 탭과 <b>같은 쿼리</b>를 쓴다. 따로 짜면
 * 화면의 탭 숫자와 실제 청구 대상이 어긋난다 (D-033 이 경고한 지점이다).
 */
@Service
@RequiredArgsConstructor
public class SecondChargeService {

	/**
	 * 같은 묶음을 다시 독촉하기까지 기다리는 시간.
	 *
	 * 알림톡은 <b>건당 발송 단가</b>가 있고, 구매자에게 하루에 여러 번 독촉이 가면
	 * 차단당한다. 셀러가 버튼을 연타해도 하루 한 번을 넘지 않는다.
	 */
	private static final Duration COOLDOWN = Duration.ofHours(24);

	private final OrderGroupRepository orderGroupRepository;
	private final SecondChargeRepository secondChargeRepository;
	private final SaleFormRepository saleFormRepository;
	private final SellerService sellerService;
	private final OutboxRecorder outboxRecorder;
	private final Clock clock;

	/** 버튼을 누르기 전에 보여 줄 값 — 대상 건수 · 금액 · 마지막 청구 시각 */
	@Transactional(readOnly = true)
	public SecondChargeResponse.Preview preview(String kakaoId, Long saleFormId) {
		List<OrderGroup> targets = targetsOf(kakaoId, saleFormId);
		Map<Long, LocalDateTime> lastCharged = lastChargedOf(targets);
		LocalDateTime now = LocalDateTime.now(clock);

		long total = targets.stream().mapToLong(OrderGroup::secondPaymentAmount).sum();
		int chargeable = (int) targets.stream()
				.filter(group -> !inCooldown(lastCharged.get(group.getId()), now))
				.count();

		return new SecondChargeResponse.Preview(
				targets.size(),
				chargeable,
				total,
				lastCharged.values().stream().max(LocalDateTime::compareTo).orElse(null));
	}

	/**
	 * 청구한다. 대상 묶음마다 알림을 다시 적재하고 이력을 한 행 남긴다.
	 *
	 * 알림 적재와 이력이 <b>한 트랜잭션에서 같이 커밋된다.</b> 이력만 남고 알림이 없으면
	 * 셀러는 보냈다고 믿는데 구매자는 못 받고, 반대면 쿨다운이 걸리지 않아 연타로 도배된다.
	 */
	@Transactional
	public SecondChargeResponse.Result charge(String kakaoId, Long saleFormId) {
		List<OrderGroup> targets = targetsOf(kakaoId, saleFormId);
		Map<Long, LocalDateTime> lastCharged = lastChargedOf(targets);
		LocalDateTime now = LocalDateTime.now(clock);

		int charged = 0;
		int skipped = 0;
		long amount = 0;

		for (OrderGroup group : targets) {
			if (inCooldown(lastCharged.get(group.getId()), now)) {
				skipped++;
				continue;
			}
			notifySecondDue(group);
			secondChargeRepository.save(SecondCharge.of(group, now));

			charged++;
			amount += group.secondPaymentAmount();
		}
		return new SecondChargeResponse.Result(charged, skipped, amount);
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 대상 집합. <b>셀러 주문 목록의 SECOND_UNPAID 탭과 같은 쿼리다.</b>
	 *
	 * 페이지를 나누지 않는다 — 청구는 대상 전부에 나가야 하고, 50건씩 끊으면
	 * 셀러가 버튼을 몇 번 눌러야 하는지 알 수 없다. 한 셀러의 미납 주문 수가
	 * 곧 이 목록의 크기다.
	 */
	private List<OrderGroup> targetsOf(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		requireOwnedForm(seller, saleFormId);

		return orderGroupRepository.findSellerOrders(
				seller.getId(), SellerOrderTab.SECOND_UNPAID.name(), saleFormId, null,
				Pageable.unpaged()).getContent();
	}

	private Map<Long, LocalDateTime> lastChargedOf(List<OrderGroup> targets) {
		return secondChargeRepository.findLastChargedAt(
				targets.stream().map(OrderGroup::getId).toList());
	}

	private boolean inCooldown(LocalDateTime lastChargedAt, LocalDateTime now) {
		return lastChargedAt != null && lastChargedAt.isAfter(now.minus(COOLDOWN));
	}

	/**
	 * 입고 시점 적재와 <b>같은 모양</b>으로 넣는다 (SaleFormService 의 notifySecondDue).
	 * 발송기가 두 경로를 구분할 이유가 없다.
	 */
	private void notifySecondDue(OrderGroup group) {
		outboxRecorder.record(OutboxAggregate.ORDER_GROUP, group.getId(),
				OutboxEventType.SECOND_PAYMENT_DUE,
				Map.of(
						"orderToken", group.getOrderToken(),
						"buyerId", group.getBuyer().getId(),
						"amount", group.secondPaymentAmount()));
	}

	/**
	 * 판매별 청구로 들어온 폼이 이 셀러 것인지 본다.
	 *
	 * 없는 폼과 남의 폼을 똑같이 404 로 돌려준다 — 403 이면 "그 id 에 폼이 있긴 하다" 가
	 * 새어 나간다. <b>쓰기 작업이라 조회보다 더 중요하다.</b>
	 */
	private void requireOwnedForm(Seller seller, Long saleFormId) {
		if (saleFormId == null) {
			return;
		}
		SaleForm form = saleFormRepository.findById(saleFormId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		if (!form.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
	}
}
