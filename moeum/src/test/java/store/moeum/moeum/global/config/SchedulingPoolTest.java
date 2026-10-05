package store.moeum.moeum.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import store.moeum.moeum.support.IntegrationTest;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 스케줄러가 스레드 하나에 줄 서지 않는다 (D-071).
 *
 * 기본값 1 이면 알림톡 장애로 릴레이가 수 분 매달릴 때 승인 대사도 같이 멈춘다.
 */
class SchedulingPoolTest extends IntegrationTest {

	@Autowired
	ThreadPoolTaskScheduler taskScheduler;

	@Test
	@DisplayName("스케줄러_스레드는_4개다")
	void 풀_크기() {
		assertThat(taskScheduler.getPoolSize()).isEqualTo(4);
		assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("batch-");
	}

	@Test
	@DisplayName("한_배치가_매달려_있어도_다른_배치는_돈다")
	void 매달린_배치가_다른_배치를_막지_않는다() throws InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch ran = new CountDownLatch(1);

		// 외부 호출이 타임아웃까지 매달린 배치를 흉내 낸다
		taskScheduler.schedule(() -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, Instant.now());

		try {
			taskScheduler.schedule(ran::countDown, Instant.now());
			assertThat(ran.await(3, TimeUnit.SECONDS))
					.as("앞 배치가 끝날 때까지 다음 배치가 기다리면 스레드가 하나뿐이라는 뜻이다")
					.isTrue();
		} finally {
			release.countDown();
		}
	}
}
