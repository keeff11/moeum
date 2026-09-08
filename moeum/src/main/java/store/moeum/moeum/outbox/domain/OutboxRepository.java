package store.moeum.moeum.outbox.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

	/**
	 * 이번에 보낼 건. <b>SKIP LOCKED 로 인스턴스 간 분산한다.</b>
	 *
	 * {@code next_attempt_at} 이 지난 것만 집는다 — 실패 백오프와 처리 중 임대가 같은 컬럼이라
	 * 이 한 줄이 "아직 쉴 시간" 과 "남이 처리 중" 을 둘 다 걸러낸다 (V6).
	 *
	 * 오래된 것부터 보낸다. 2차금 청구 알림은 늦을수록 셀러의 미수가 길어진다.
	 */
	@Query(value = """
			SELECT * FROM outbox
			 WHERE status = 'PENDING'
			   AND next_attempt_at <= :now
			 ORDER BY next_attempt_at, id
			 LIMIT :limit
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Outbox> findSendable(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
