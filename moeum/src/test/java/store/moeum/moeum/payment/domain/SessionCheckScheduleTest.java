package store.moeum.moeum.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCheckScheduleTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 23, 50);

	@Test
	@DisplayName("첫_조회는_세션을_붙이고_30초_뒤다")
	void 첫_조회() {
		assertThat(SessionCheckSchedule.first(NOW)).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	@DisplayName("간격은_1_2_4_8분으로_늘다가_10분에서_멈춘다")
	void 간격() {
		assertThat(SessionCheckSchedule.interval(0)).isEqualTo(Duration.ofMinutes(1));
		assertThat(SessionCheckSchedule.interval(1)).isEqualTo(Duration.ofMinutes(2));
		assertThat(SessionCheckSchedule.interval(2)).isEqualTo(Duration.ofMinutes(4));
		assertThat(SessionCheckSchedule.interval(3)).isEqualTo(Duration.ofMinutes(8));
		assertThat(SessionCheckSchedule.interval(4)).isEqualTo(Duration.ofMinutes(10));
		assertThat(SessionCheckSchedule.interval(100)).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	@DisplayName("상한_횟수에_닿으면_다음_차례가_없다")
	void 상한() {
		assertThat(SessionCheckSchedule.next(NOW, SessionCheckSchedule.MAX_CHECKS - 2)).isNotNull();
		assertThat(SessionCheckSchedule.next(NOW, SessionCheckSchedule.MAX_CHECKS - 1)).isNull();
	}

	@Test
	@DisplayName("상한까지_물으면_대략_하루다")
	void 상한_기간() {
		Duration total = SessionCheckSchedule.FIRST_DELAY;
		for (int checks = 0; checks < SessionCheckSchedule.MAX_CHECKS - 1; checks++) {
			total = total.plus(SessionCheckSchedule.interval(checks));
		}
		assertThat(total).isBetween(Duration.ofHours(23), Duration.ofHours(26));
	}
}
