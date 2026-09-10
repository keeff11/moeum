package store.moeum.moeum.global.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 어디에도 연결되지 않은 업로드 이미지를 버킷에서 지운다. 매일 새벽 한 번 돈다.
 *
 * <b>고아가 생기는 경로는 넷이다.</b> 셋은 업로드가 presigned PUT 이라서 생긴다 —
 * 브라우저가 S3 로 직접 올리므로 서버는 파일이 올라간 사실조차 모르고,
 * 폼 저장 요청이 와야 그때 처음 키를 알게 된다 ({@link ImageStorage} 참고).
 *  <ol>
 *    <li>이미지를 올리고 폼을 저장하지 않은 채 창을 닫는다 — 아무도 그 키를 모른다</li>
 *    <li>폼 수정에서 이미지를 뺀다 — {@code replaceImages} 는 DB 행만 갈아끼운다</li>
 *    <li>셀러가 프로필 사진을 바꾼다 — 이전 키를 덮어쓰기만 한다</li>
 *    <li>폼이나 셀러가 지워진다 — 마찬가지로 객체는 남는다</li>
 *  </ol>
 *
 * <b>S3 수명주기 규칙으로는 못 푼다.</b> 규칙은 "며칠 지났는가" 만 알지 "DB 가 이 키를 쓰고 있는가"
 * 는 모른다. 키가 {@code sale-forms/{sellerId}/{uuid}} 라 임시·확정을 접두사로 나눌 수도 없어서,
 * 규칙을 그냥 걸면 잘 팔리고 있는 상품 이미지가 만료된다. 그래서 DB 를 보고 판정한다.
 *
 * <b>이 배치는 되돌릴 수 없는 일을 한다.</b> 그래서 세 겹으로 막는다.
 *  <ul>
 *    <li>{@code dry-run} 기본값 true — 켜서 로그부터 본다</li>
 *    <li>유예 시간 — 올린 직후의 객체는 아직 저장 안 한 작업 중일 수 있다</li>
 *    <li>비율 안전장치 — {@link OrphanSweepPlan} 이 대량 삭제를 막는다</li>
 *  </ul>
 * 여기에 버킷 versioning 을 같이 켜 둔다. 위 셋을 다 뚫어도 되돌릴 수 있는 것은 그것뿐이다.
 */
@Slf4j
@Component
public class OrphanImageSweepBatch {

	private final List<ImageKeySource> keySources;
	private final ImageStorage imageStorage;
	private final OrphanSweepProperties properties;

	public OrphanImageSweepBatch(List<ImageKeySource> keySources,
	                             ImageStorage imageStorage,
	                             OrphanSweepProperties properties) {
		if (keySources.isEmpty()) {
			// 참조처가 하나도 없으면 버킷의 모든 객체가 고아로 보인다. 뜨지 않는 편이 낫다
			throw new IllegalStateException("ImageKeySource 구현체가 하나도 없다. 청소 배치를 띄울 수 없다");
		}
		this.keySources = keySources;
		this.imageStorage = imageStorage;
		this.properties = properties;
	}

	/**
	 * 하루 한 번이면 충분하다. 다른 배치들처럼 1분마다 돌릴 이유가 없다 —
	 * 버킷 전체를 훑는 일이고, 고아 파일이 하루 더 남아 있는 비용은 사실상 0 이다.
	 *
	 * 테스트에서는 {@code "-"} 로 꺼 둔다. 실제 AWS 를 부르게 두면 안 된다.
	 */
	@Scheduled(cron = "${moeum.batch.orphan-sweep.cron:0 0 4 * * *}", zone = "Asia/Seoul")
	public void run() {
		sweepOnce();
	}

	/** 한 번의 청소. 테스트와 운영 콘솔이 직접 부를 수 있게 열어 둔다 */
	public OrphanSweepPlan sweepOnce() {
		List<ImageStorage.StoredObject> objects = imageStorage.listAll();
		if (objects.isEmpty()) {
			// 버킷이 비었거나 설정이 안 된 환경이다 (로컬)
			return new OrphanSweepPlan(List.of(), 0, null);
		}

		Set<String> referenced = collectReferencedKeys();
		Instant cutoff = Instant.now().minus(properties.grace());

		OrphanSweepPlan plan = OrphanSweepPlan.of(referenced, objects, cutoff, properties.maxDeleteRatio());

		if (plan.aborted()) {
			log.error("고아 이미지 청소 중단: {}", plan.abortReason());
			return plan;
		}
		if (plan.targets().isEmpty()) {
			log.info("고아 이미지 없음: 훑음={}, 참조={}", plan.scanned(), referenced.size());
			return plan;
		}

		if (properties.dryRun()) {
			// 지웠을 목록을 남긴다. 여기에 살아 있어야 할 이미지가 섞여 있으면 참조처를 빠뜨린 것이다
			log.warn("고아 이미지 청소 (dry-run, 지우지 않음): 대상={}건, 훑음={}, 참조={}, 키={}",
					plan.targets().size(), plan.scanned(), referenced.size(), preview(plan.targets()));
			return plan;
		}

		int deleted = imageStorage.deleteAll(plan.targets());
		log.info("고아 이미지 청소: 삭제={}건, 훑음={}, 참조={}, 유예={}시간",
				deleted, plan.scanned(), referenced.size(), properties.grace().toHours());
		return plan;
	}

	/**
	 * 참조처를 전부 모은다. <b>여기 안 들어온 키는 삭제 대상이 된다.</b>
	 *
	 * 참조처별 건수를 로그로 남긴다 — 한쪽 조회가 조용히 빈 결과를 내는 것이
	 * 이 배치에서 가장 위험한 고장인데, 비율 안전장치에 안 걸릴 만큼 작으면 로그로만 잡힌다.
	 */
	private Set<String> collectReferencedKeys() {
		Set<String> keys = new HashSet<>();
		for (ImageKeySource source : keySources) {
			List<String> sourceKeys = source.referencedImageKeys();
			log.info("참조 이미지 키: {}={}건", source.sourceName(), sourceKeys.size());
			keys.addAll(sourceKeys);
		}
		return keys;
	}

	private static String preview(List<String> targets) {
		return targets.size() <= 20 ? targets.toString()
				: targets.subList(0, 20) + " 외 " + (targets.size() - 20) + "건";
	}
}
