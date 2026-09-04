package store.moeum.moeum.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 데드락 · 락 타임아웃 재시도(@Retryable)와 배치 스케줄링(@Scheduled)을 켠다.
 * OutOfStockException 처럼 재시도해도 결과가 같은 예외는 재시도 대상에서 제외한다.
 */
@Configuration
@EnableRetry
@EnableScheduling
public class RetryConfig {
}
