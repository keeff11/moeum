package store.moeum.moeum.order.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public interface SecondChargeRepository extends JpaRepository<SecondCharge, Long> {

	/** 쿨다운 판정 — 이 묶음을 마지막으로 언제 청구했나 */
	Optional<SecondCharge> findTopByOrderGroupIdOrderByChargedAtDesc(Long orderGroupId);

	/**
	 * 여러 묶음의 마지막 청구 시각을 한 번에.
	 *
	 * <b>일괄 청구는 대상이 수백 건일 수 있다.</b> 묶음마다 위 메서드를 부르면
	 * 그 수만큼 조회가 나간다.
	 */
	@Query("""
			select c.orderGroup.id, max(c.chargedAt)
			  from SecondCharge c
			 where c.orderGroup.id in :orderGroupIds
			 group by c.orderGroup.id
			""")
	List<Object[]> findLastChargedAtRows(@Param("orderGroupIds") List<Long> orderGroupIds);

	/** 위 결과를 쓰기 좋은 모양으로 접어 준다 */
	default Map<Long, LocalDateTime> findLastChargedAt(List<Long> orderGroupIds) {
		if (orderGroupIds.isEmpty()) {
			return Map.of();
		}
		return findLastChargedAtRows(orderGroupIds).stream()
				.collect(Collectors.toMap(row -> (Long) row[0], row -> (LocalDateTime) row[1]));
	}
}
