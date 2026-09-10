package store.moeum.moeum.global.storage;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 한 번의 청소에서 "무엇을 지울지" 를 정한 결과. 판정만 하고 S3 는 건드리지 않는다.
 *
 * <b>S3 도 DB 도 모르는 순수 계산으로 떼어 둔 이유</b>는 이 판정이 이 기능에서 유일하게
 * 되돌릴 수 없는 부분이기 때문이다. 유예 시간을 잘못 재거나 안전장치가 헐거우면
 * 살아 있는 이미지가 사라진다. 여기가 분리돼 있어야 AWS 없이 그 경계를 테스트로 못 박을 수 있다.
 *
 * @param targets     지울 키
 * @param scanned     훑은 객체 수
 * @param abortReason 중단 사유. null 이 아니면 {@code targets} 는 무시하고 아무것도 지우지 않는다
 */
public record OrphanSweepPlan(List<String> targets, int scanned, String abortReason) {

	/**
	 * 비율 안전장치를 적용하지 않는 삭제 건수.
	 *
	 * 비율만으로 막으면 객체가 몇 개 없는 초기에는 정상 고아도 영영 못 지운다 —
	 * 3개 중 3개가 고아면 100% 라서 매번 걸린다. 이 수 아래는 사고가 나도 피해가 작으니 통과시킨다.
	 */
	private static final int ALWAYS_ALLOWED = 50;

	public boolean aborted() {
		return abortReason != null;
	}

	/**
	 * @param referencedKeys DB 가 참조 중인 키. <b>여기 없는 키가 삭제 후보가 된다</b>
	 * @param objects        버킷에 실제로 있는 객체
	 * @param cutoff         이 시각 이후에 올라온 객체는 아직 폼 저장을 기다리는 중일 수 있어 건드리지 않는다
	 * @param maxDeleteRatio 전체 대비 이 비율을 넘게 지우게 되면 중단한다
	 */
	public static OrphanSweepPlan of(Set<String> referencedKeys,
	                                 List<ImageStorage.StoredObject> objects,
	                                 Instant cutoff,
	                                 double maxDeleteRatio) {
		int scanned = objects.size();

		List<String> targets = objects.stream()
				.filter(object -> !referencedKeys.contains(object.key()))
				.filter(object -> object.lastModified().isBefore(cutoff))
				.map(ImageStorage.StoredObject::key)
				.toList();

		// 참조 조회가 통째로 실패하면 전부 고아로 보인다. 그때 실제로 지우지 않는 것이 이 조건의 전부다
		if (targets.size() > ALWAYS_ALLOWED && targets.size() > scanned * maxDeleteRatio) {
			String reason = "삭제 대상이 전체의 %.0f%% (%d/%d) 다. 참조 조회가 깨졌을 수 있어 중단한다"
					.formatted(targets.size() * 100.0 / scanned, targets.size(), scanned);
			return new OrphanSweepPlan(targets, scanned, reason);
		}

		return new OrphanSweepPlan(targets, scanned, null);
	}
}
