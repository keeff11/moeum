package store.moeum.moeum.payment.refund;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * point3 의 EOB 차단 시간대 — <b>매일 23:30 이상 00:30 미만 (KST)</b> (point3-api 8절).
 *
 * 이 시간대에는 point3 가 취소를 받지 않는다. 어차피 실패할 요청을 보내지 않으려고 미리 본다.
 *
 * <b>결제(승인)는 막히지 않는다.</b> 취소에만 걸리는 제약이다.
 *
 * 화면에서는 버튼 자체를 비활성화한다 — 눌러서 튕기는 건 사용자에게 그냥 고장으로 보인다.
 * "실패" 가 아니라 "00:30 이후에 가능" 으로 안내해야 하므로 {@link #nextOpenAt} 을 함께 준다.
 */
public final class EobWindow {

	private static final LocalTime START = LocalTime.of(23, 30);
	private static final LocalTime END = LocalTime.of(0, 30);

	private EobWindow() {
	}

	/** 지금 취소가 막히는 시간대인가. 23:30:00 은 막히고 00:30:00 은 열린다 */
	public static boolean isBlocked(LocalDateTime now) {
		LocalTime time = now.toLocalTime();
		// 자정을 넘는 구간이라 OR 로 본다
		return !time.isBefore(START) || time.isBefore(END);
	}

	/** 다시 취소할 수 있게 되는 시각. 막힌 상태가 아니면 null */
	public static LocalDateTime nextOpenAt(LocalDateTime now) {
		if (!isBlocked(now)) {
			return null;
		}
		LocalDateTime today = now.toLocalDate().atTime(END);
		// 23:30~23:59 이면 다음 날 00:30, 00:00~00:29 이면 오늘 00:30
		return now.toLocalTime().isBefore(END) ? today : today.plusDays(1);
	}

	/**
	 * {@code from} 부터 {@code to} 까지 중 <b>취소가 열려 있던 시간</b>.
	 *
	 * 미확정 취소가 얼마나 묵었는지 잴 때 쓴다. 23:20 에 들어온 건이 00:40 에 70분이 아니라
	 * 20분이어야 한다 — 그 사이 한 시간은 대사 배치도 쉬어서 아무도 손댈 수 없었다.
	 */
	public static Duration openDuration(LocalDateTime from, LocalDateTime to) {
		if (!to.isAfter(from)) {
			return Duration.ZERO;
		}
		Duration open = Duration.between(from, to);
		// from 이 00:10 이면 전날 23:30 에 시작한 창에 걸려 있다. 하루 앞에서부터 본다
		for (LocalDate day = from.toLocalDate().minusDays(1); !day.isAfter(to.toLocalDate()); day = day.plusDays(1)) {
			LocalDateTime start = day.atTime(START);
			LocalDateTime end = day.plusDays(1).atTime(END);
			LocalDateTime overlapStart = start.isAfter(from) ? start : from;
			LocalDateTime overlapEnd = end.isBefore(to) ? end : to;
			if (overlapEnd.isAfter(overlapStart)) {
				open = open.minus(Duration.between(overlapStart, overlapEnd));
			}
		}
		return open;
	}
}
