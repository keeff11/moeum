package store.moeum.moeum.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 고아 이미지 청소 설정. 기본값은 전부 "안전한 쪽" 이다 — 지우는 일이라 되돌릴 수 없다.
 *
 * @param dryRun   true 면 지울 목록만 로그로 남기고 실제로는 안 지운다. <b>기본값이 true 다</b> —
 *                 참조처를 빠뜨렸는지 로그로 확인한 뒤에 끄라는 뜻이다
 * @param grace    올라온 지 이 시간이 안 지난 객체는 건드리지 않는다. 셀러가 이미지를 올려 두고
 *                 상세 설명을 쓰는 동안은 아직 어느 폼에도 연결돼 있지 않다 — 짧게 잡으면 그걸 지운다
 * @param maxDeleteRatio 한 번에 전체의 이 비율을 넘게 지우게 되면 중단한다.
 *                 참조 조회가 버그로 빈 결과를 내면 버킷 전체가 고아로 판정되는데, 그걸 막는 마지막 선이다
 */
@ConfigurationProperties(prefix = "moeum.batch.orphan-sweep")
public record OrphanSweepProperties(
		Boolean dryRun,
		Duration grace,
		Double maxDeleteRatio
) {

	public OrphanSweepProperties {
		dryRun = (dryRun == null) || dryRun;
		grace = (grace == null) ? Duration.ofHours(24) : grace;
		maxDeleteRatio = (maxDeleteRatio == null) ? 0.30 : maxDeleteRatio;
	}
}
