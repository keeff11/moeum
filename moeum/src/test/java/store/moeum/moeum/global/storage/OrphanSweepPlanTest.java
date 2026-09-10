package store.moeum.moeum.global.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고아 판정 (D-041).
 *
 * 이 프로젝트에서 유일하게 되돌릴 수 없는 배치라 경계를 여기서 못 박는다.
 * S3 도 DB 도 안 붙는다 — 판정은 순수 계산이고, 그래야 이 경계만 따로 볼 수 있다.
 */
class OrphanSweepPlanTest {

	private static final Instant NOW = Instant.parse("2026-09-10T04:00:00Z");
	private static final Instant CUTOFF = NOW.minusSeconds(24 * 3600);

	private static final Instant OLD = CUTOFF.minusSeconds(60);     // 유예 지남
	private static final Instant FRESH = CUTOFF.plusSeconds(60);    // 유예 안 지남

	@Test
	@DisplayName("폼에_연결되지_않고_유예도_지난_객체만_지운다")
	void 고아만_지운다() {
		OrphanSweepPlan plan = OrphanSweepPlan.of(
				Set.of("sale-forms/1/linked.jpg"),
				List.of(object("sale-forms/1/linked.jpg", OLD),
						object("sale-forms/1/orphan.jpg", OLD)),
				CUTOFF, 0.30);

		assertThat(plan.aborted()).isFalse();
		assertThat(plan.targets()).containsExactly("sale-forms/1/orphan.jpg");
		assertThat(plan.scanned()).isEqualTo(2);
	}

	@Test
	@DisplayName("방금_올린_객체는_아직_저장_전일_수_있어_건드리지_않는다")
	void 유예_안_지난_것은_남긴다() {
		// 셀러가 이미지를 올려 두고 상세 설명을 쓰는 중이다. 아직 어느 폼에도 연결돼 있지 않다
		OrphanSweepPlan plan = OrphanSweepPlan.of(
				Set.of(),
				List.of(object("sale-forms/1/writing-now.jpg", FRESH)),
				CUTOFF, 0.30);

		assertThat(plan.targets()).isEmpty();
	}

	@Test
	@DisplayName("참조_조회가_빈_결과를_내면_대량_삭제를_막는다")
	void 대량_삭제를_막는다() {
		// 쿼리가 깨져 참조 집합이 비면 버킷 전체가 고아로 보인다. 그때 실제로 지우지 않는 것이 이 조건이다
		List<ImageStorage.StoredObject> objects = IntStream.range(0, 200)
				.mapToObj(i -> object("sale-forms/1/live-" + i + ".jpg", OLD))
				.toList();

		OrphanSweepPlan plan = OrphanSweepPlan.of(Set.of(), objects, CUTOFF, 0.30);

		assertThat(plan.aborted()).isTrue();
		assertThat(plan.abortReason()).contains("100%", "200/200");
	}

	@Test
	@DisplayName("비율을_넘어도_건수가_적으면_지운다")
	void 소량은_비율에_걸리지_않는다() {
		// 객체가 몇 개 없는 초기에는 정상 고아도 100% 가 된다. 비율만으로 막으면 영영 안 지워진다
		OrphanSweepPlan plan = OrphanSweepPlan.of(
				Set.of(),
				List.of(object("sale-forms/1/a.jpg", OLD), object("sale-forms/1/b.jpg", OLD)),
				CUTOFF, 0.30);

		assertThat(plan.aborted()).isFalse();
		assertThat(plan.targets()).hasSize(2);
	}

	@Test
	@DisplayName("비율_안쪽이면_많이_지워도_통과한다")
	void 비율_안쪽은_통과한다() {
		// 100개 중 60개는 살아 있고 40개가 고아다. 40% 지만 참조가 멀쩡하므로 정상 청소다
		List<ImageStorage.StoredObject> objects = IntStream.range(0, 100)
				.mapToObj(i -> object("sale-forms/1/k" + i + ".jpg", OLD))
				.toList();
		Set<String> referenced = IntStream.range(0, 60)
				.mapToObj(i -> "sale-forms/1/k" + i + ".jpg")
				.collect(java.util.stream.Collectors.toSet());

		OrphanSweepPlan plan = OrphanSweepPlan.of(referenced, objects, CUTOFF, 0.50);

		assertThat(plan.aborted()).isFalse();
		assertThat(plan.targets()).hasSize(40);
	}

	@Test
	@DisplayName("버킷이_비면_아무_일도_없다")
	void 빈_버킷() {
		OrphanSweepPlan plan = OrphanSweepPlan.of(Set.of(), List.of(), CUTOFF, 0.30);

		assertThat(plan.aborted()).isFalse();
		assertThat(plan.targets()).isEmpty();
		assertThat(plan.scanned()).isZero();
	}

	private static ImageStorage.StoredObject object(String key, Instant lastModified) {
		return new ImageStorage.StoredObject(key, lastModified);
	}
}
