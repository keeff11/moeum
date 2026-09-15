package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderItem;
import store.moeum.moeum.order.domain.StockHold;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.ProductOptionRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 재고 카운터 이동. 폼(sale_form)과 옵션(product_option) 두 층을 <b>항상 같이</b> 움직인다 (D-054).
 *
 * 확보 · 해제 · 확정 · 되돌림 네 동작이 네 군데(주문 생성 · 만료 배치 · 결제 확정 · 환불)에
 * 흩어져 있었고, 옵션 층이 생기면서 한 군데라도 빠뜨리면 옵션 카운터가 어긋난다.
 * 그래서 폼 쿼리를 직접 부르던 자리를 전부 이 클래스로 모았다.
 *
 * <b>이 클래스는 트랜잭션을 열지 않는다.</b> 부르는 쪽의 트랜잭션 안에서 돈다.
 * 상태 전이(StockHold.release/commit)와 멱등 가드도 부르는 쪽에 그대로 있다 —
 * 여기는 시키는 대로 카운터만 옮긴다.
 *
 * 옵션 수량은 홀드 행이 아니라 order_item 에서 읽는다. 항목이 옵션과 수량을 이미
 * 스냅샷하고 있어서 홀드 테이블을 옵션 단위로 쪼갤 이유가 없다.
 *
 * 잠금 순서: 폼 → 그 폼의 옵션 id 오름차순. 두 요청이 반대 순서로 잠그면 데드락이 난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockLedger {

	private final SaleFormRepository saleFormRepository;
	private final ProductOptionRepository optionRepository;

	/**
	 * ★ 재고 확보. 폼 조건부 UPDATE 한 방, 그다음 옵션 조건부 UPDATE 한 방씩.
	 * 어느 하나라도 영향 행 0이면 품절 또는 마감이다.
	 *
	 * 여기서 예외를 던지면 같은 트랜잭션에서 앞서 확보한 것들도 함께 롤백된다.
	 * 배송비가 묶음당 1회라 일부만 성공시키면 배송비를 나눌 방법이 없다 (D-009).
	 *
	 * @param qtyByOption 옵션 id → 수량. 이 폼의 옵션만 들어 있어야 한다
	 */
	public void acquire(SaleForm form, List<ProductOption> options, Map<Long, Integer> qtyByOption) {
		int totalQty = options.stream().mapToInt(option -> qtyByOption.get(option.getId())).sum();

		int affected = saleFormRepository.hold(form.getId(), totalQty);
		if (affected == 0) {
			log.info("재고 확보 실패: saleFormId={}, qty={}, status={}",
					form.getId(), totalQty, form.getStatus());
			// 일시중지는 따로 말한다. hold 의 WHERE 가 status='SELLING' 이라 여기 같이 떨어지는데,
			// '마감되었습니다' 로 답하면 셀러가 곧 다시 열 판매를 구매자가 끝난 것으로 본다
			if (form.getStatus() == SaleFormStatus.PAUSED) {
				throw new OutOfStockException(ErrorCode.SALE_PAUSED, form.getId(),
						"'" + form.getTitle() + "' 은 판매자가 잠시 판매를 멈췄습니다.");
			}
			throw new OutOfStockException(form.getId(),
					"'" + form.getTitle() + "' 의 재고가 부족하거나 판매가 마감되었습니다.");
		}

		List<ProductOption> sorted = options.stream()
				.sorted(Comparator.comparing(ProductOption::getId))
				.toList();
		for (ProductOption option : sorted) {
			int qty = qtyByOption.get(option.getId());
			if (optionRepository.hold(option.getId(), qty) == 0) {
				log.info("옵션 재고 확보 실패: saleFormId={}, optionId={}, qty={}",
						form.getId(), option.getId(), qty);
				throw new OutOfStockException(form.getId(),
						"'" + form.getTitle() + "' 의 '" + option.getName() + "' 옵션 재고가 부족합니다.");
			}
		}
	}

	/**
	 * 홀드 해제 — 폼과 옵션의 held 를 되돌린다.
	 *
	 * @return 폼 쿼리의 영향 행. 0이면 held 가 이미 그만큼 없는 어긋난 상태다
	 */
	public int release(StockHold hold) {
		int affected = saleFormRepository.releaseHold(hold.getSaleForm().getId(), hold.getQty());
		for (OrderItem item : itemsOf(hold.getOrder())) {
			if (optionRepository.releaseHold(item.getOption().getId(), item.getQty()) == 0) {
				log.warn("옵션 홀드 해제 시 재고 반환 실패: holdId={}, optionId={}, qty={}",
						hold.getId(), item.getOption().getId(), item.getQty());
			}
		}
		return affected;
	}

	/**
	 * 홀드 확정 — held 에서 sold 로 옮긴다. 승인 완료(captured) 후에만 부른다.
	 *
	 * @return 폼 쿼리의 영향 행
	 */
	public int commit(StockHold hold) {
		int affected = saleFormRepository.commitHold(hold.getSaleForm().getId(), hold.getQty());
		for (OrderItem item : itemsOf(hold.getOrder())) {
			if (optionRepository.commitHold(item.getOption().getId(), item.getQty()) == 0) {
				log.error("옵션 홀드 확정 실패: holdId={}, optionId={}, qty={}",
						hold.getId(), item.getOption().getId(), item.getQty());
			}
		}
		return affected;
	}

	/**
	 * 취소된 수량을 재고로 되돌린다 (D-024). 되돌릴지 말지는 호출자가 판단한다.
	 *
	 * @return 폼 쿼리의 영향 행
	 */
	public int restoreSold(Order order) {
		int affected = saleFormRepository.restoreSold(order.getSaleForm().getId(), order.getQty());
		for (OrderItem item : itemsOf(order)) {
			if (optionRepository.restoreSold(item.getOption().getId(), item.getQty()) == 0) {
				log.error("옵션 재고 되돌리기 실패: orderId={}, optionId={}, qty={}",
						order.getId(), item.getOption().getId(), item.getQty());
			}
		}
		return affected;
	}

	/** 옵션 id 오름차순. 확보 때와 같은 순서로 잠근다 */
	private static List<OrderItem> itemsOf(Order order) {
		return order.getItems().stream()
				.sorted(Comparator.comparing(item -> item.getOption().getId()))
				.toList();
	}
}
