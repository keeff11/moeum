package store.moeum.moeum.payment.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 결제 내역(G10) 칩 옆의 숫자 (D-059).
 *
 * <b>칩마다 COUNT 를 날리지 않는다.</b> 한 번의 {@code GROUP BY} 로 받아 여기서 편다 —
 * G6 의 탭 배지와 같은 판단이다.
 *
 * 칩 필터를 뺀 나머지 조건(검색어 · 판매별 필터)은 그대로 걸린 값이다.
 * 검색 중이면 칩도 그 검색 결과의 숫자여야 화면이 맞는다.
 *
 * @param pending 칩이 없는 '확인 중'. {@code total} 에는 들어 있다 — 어느 칩에도
 *                잡히지 않는 줄이 있으면 칩 숫자의 합과 전체가 안 맞는데, 그게 정상이다
 */
public record SellerPaymentCounts(long total, long paid, long settled,
                                  long canceling, long canceled, long failed, long pending) {

	/** 집계 쿼리가 준 {@code (상태, 건수)} 줄을 편다. 한 건도 없는 칩은 0 이다 */
	public static SellerPaymentCounts of(Iterable<Object[]> rows) {
		Map<SellerPaymentStatus, Long> tally = new EnumMap<>(SellerPaymentStatus.class);
		long total = 0;

		for (Object[] row : rows) {
			SellerPaymentStatus status = SellerPaymentStatus.valueOf((String) row[0]);
			long count = ((Number) row[1]).longValue();
			tally.merge(status, count, Long::sum);
			total += count;
		}

		return new SellerPaymentCounts(total,
				at(tally, SellerPaymentStatus.PAID),
				at(tally, SellerPaymentStatus.SETTLED),
				at(tally, SellerPaymentStatus.CANCELING),
				at(tally, SellerPaymentStatus.CANCELED),
				at(tally, SellerPaymentStatus.FAILED),
				at(tally, SellerPaymentStatus.PENDING));
	}

	public long at(SellerPaymentStatus status) {
		return switch (status) {
			case PAID -> paid;
			case SETTLED -> settled;
			case CANCELING -> canceling;
			case CANCELED -> canceled;
			case FAILED -> failed;
			case PENDING -> pending;
		};
	}

	private static long at(Map<SellerPaymentStatus, Long> tally, SellerPaymentStatus status) {
		return tally.getOrDefault(status, 0L);
	}
}
