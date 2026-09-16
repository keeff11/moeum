package store.moeum.moeum.dev.console;

import java.util.List;
import java.util.Map;

/**
 * 주문 묶음 하나를 세로로 펼친 결과.
 *
 * 결제 사고를 볼 때 매번 order_group → payment → payment_event 를 손으로 조인해서
 * 보는 일을 없애려고 만들었다. 화면 하나에서 상태 전이 이력까지 같이 본다.
 */
public record TraceResult(
		boolean found,
		String query,
		Map<String, Object> orderGroup,
		Map<String, Object> buyer,
		Map<String, Object> seller,
		List<Map<String, Object>> orders,
		List<Map<String, Object>> payments,
		List<Map<String, Object>> events,
		List<Map<String, Object>> refunds,
		List<Map<String, Object>> holds,
		List<Map<String, Object>> outbox
) {

	public static TraceResult notFound(String query) {
		return new TraceResult(false, query, null, null, null,
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
	}
}
