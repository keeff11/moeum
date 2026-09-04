package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.domain.StockHoldRepository;
import store.moeum.moeum.saleform.domain.SaleFormRepository;

import java.util.List;

/**
 * 만료된 홀드를 회수한다. 1분마다 돈다.
 *
 * <b>이게 최종 안전망이다.</b> 프론트의 /release 는 브라우저 강제 종료·앱 전환이면 오지 않는다.
 * 이 배치가 없으면 이탈자들이 물고 있는 재고가 영원히 안 풀린다.
 *
 * 두 가지를 지킨다.
 *  - FOR UPDATE SKIP LOCKED 로 인스턴스 간 분산. 남이 잡은 행은 기다리지 않고 건너뛴다
 *  - 멱등 가드. 같은 홀드를 두 번 처리해도 재고가 두 번 돌아가지 않는다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HoldExpiryBatch {

	/** 한 번에 집는 양. 락을 오래 쥐지 않도록 끊어서 처리한다 */
	private static final int BATCH_SIZE = 100;

	private final StockHoldRepository stockHoldRepository;
	private final SaleFormRepository saleFormRepository;

	@Transactional
	@Scheduled(fixedDelayString = "${moeum.batch.hold-expiry-delay:60000}")
	public void run() {
		int released = expireOnce();
		if (released > 0) {
			log.info("만료 홀드 회수: {}건", released);
		}
	}

	/**
	 * 한 번의 회수. 테스트가 직접 부를 수 있게 열어 둔다.
	 *
	 * CAPTURE_PENDING 인 묶음은 조회 쿼리에서 제외된다 —
	 * 승인 결과를 모르는 상태에서 재고를 남에게 넘기면 초과 판매가 된다.
	 */
	@Transactional
	public int expireOnce() {
		List<StockHold> expired = stockHoldRepository.findExpiredForUpdate(BATCH_SIZE);

		int released = 0;
		for (StockHold hold : expired) {
			if (!hold.release()) {
				continue;
			}
			int affected = saleFormRepository.releaseHold(hold.getSaleForm().getId(), hold.getQty());
			if (affected == 0) {
				// held 가 이미 그만큼 없다. 데이터가 어긋난 상태라 조용히 넘기지 않는다
				log.warn("홀드 회수 시 재고 반환 실패: holdId={}, saleFormId={}, qty={}",
						hold.getId(), hold.getSaleForm().getId(), hold.getQty());
				continue;
			}
			hold.getOrder().getOrderGroup().expire();
			released++;
		}
		return released;
	}
}
