package store.moeum.moeum.payment.refund;

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
}
