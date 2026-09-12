package store.moeum.moeum.order.infra;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배송조회 호출 수를 줄이는 캐시 (D-048).
 *
 * <b>이용권이 월 100건이다.</b> 캐시가 없으면 구매자가 배송조회 화면을 열 때마다 한 건씩
 * 쓰는데, 배송을 기다리며 하루 두세 번 열어 보는 것이 보통이라 <b>주문 대여섯 건이면
 * 한 달치가 사라진다.</b> 여기서 막는 것이 기능의 전제 조건이다.
 *
 * 세 가지를 다르게 잡는다.
 * <ul>
 *   <li><b>택배사 목록 — 하루.</b> 132곳이고 거의 안 늘어난다. 셀러가 등록 화면을
 *       백 번 열어도 한 건이다</li>
 *   <li><b>배송완료 — 다시 안 부른다.</b> 끝난 배송은 더 바뀔 것이 없다.
 *       주문 하나가 쓰는 조회 수가 이걸로 유한해진다</li>
 *   <li><b>진행 중 — 30분.</b> 새로고침 연타를 흡수한다. 택배 단계는 그보다 자주
 *       바뀌지 않는다</li>
 * </ul>
 *
 * <b>실패도 짧게 캐시한다.</b> 그쪽이 죽어 있으면 새로고침마다 한 건씩 태우게 된다.
 * 다만 5분으로 짧게 둔다 — 송장 등록 직후에는 택배사가 아직 인식하지 못해 실패하는
 * 것이 흔한데, 길게 잡으면 그동안 계속 "조회할 수 없음" 으로 보인다.
 *
 * <b>메모리에 둔다.</b> 재배포하면 날아가지만 인스턴스가 한 대라 문제가 안 되고,
 * DB 에 두려면 표가 하나 늘어난다. 한도가 정말 빠듯해지면 그때 옮긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackingCache {

	/**
	 * 이 수를 넘으면 만료된 것부터 걷어 낸다.
	 *
	 * 상한이 없으면 송장 하나마다 한 칸씩 쌓여 t3.small 의 힙을 갉아먹는다.
	 */
	private static final int MAX_ENTRIES = 2_000;

	private final SmartTrackerProperties properties;

	private final Map<String, Entry<SmartTrackerClient.Tracking>> tracking = new ConcurrentHashMap<>();
	private volatile Entry<List<SmartTrackerClient.Carrier>> carriers;

	// ---------------------------------------------------------------- 택배사 목록

	public Optional<List<SmartTrackerClient.Carrier>> carriers() {
		Entry<List<SmartTrackerClient.Carrier>> cached = this.carriers;
		return (cached == null || cached.expired()) ? Optional.empty() : Optional.of(cached.value());
	}

	public void putCarriers(List<SmartTrackerClient.Carrier> value) {
		this.carriers = Entry.of(value, properties.carrierCacheTtl());
	}

	// ---------------------------------------------------------------- 배송조회

	/**
	 * @return 캐시된 결과. 값이 없으면 아직 안 불렀거나 만료된 것이고,
	 *         값이 있는데 {@code null} 이면 <b>직전 조회가 실패했다</b>는 뜻이다
	 */
	public Optional<Optional<SmartTrackerClient.Tracking>> get(String carrierCode, String trackingNo) {
		Entry<SmartTrackerClient.Tracking> cached = tracking.get(key(carrierCode, trackingNo));
		if (cached == null || cached.expired()) {
			return Optional.empty();
		}
		return Optional.of(Optional.ofNullable(cached.value()));
	}

	/**
	 * 조회 성공을 담는다.
	 *
	 * <b>배송이 끝났으면 만료가 없다.</b> 더 물어볼 것이 없는데 30분마다 다시 부르면
	 * 끝난 주문이 계속 이용권을 쓴다.
	 */
	public void put(String carrierCode, String trackingNo, SmartTrackerClient.Tracking value) {
		Duration ttl = value.completed() ? null : properties.trackingCacheTtl();
		store(key(carrierCode, trackingNo), Entry.of(value, ttl));
	}

	/** 조회 실패를 담는다. 성공보다 훨씬 짧다 */
	public void putFailure(String carrierCode, String trackingNo) {
		store(key(carrierCode, trackingNo), Entry.of(null, properties.failureCacheTtl()));
	}

	/**
	 * 전부 비운다.
	 *
	 * 테스트가 쓴다 — 캐시가 싱글턴이라 비우지 않으면 앞 테스트가 담아 둔 값이 넘어간다.
	 * 운영에서도 쓸 데가 있다: 택배사 목록이 바뀌었는데 하루를 기다리기 싫을 때다.
	 */
	public void clear() {
		tracking.clear();
		carriers = null;
	}

	private void store(String key, Entry<SmartTrackerClient.Tracking> entry) {
		if (tracking.size() >= MAX_ENTRIES) {
			int before = tracking.size();
			tracking.values().removeIf(Entry::expired);
			if (tracking.size() >= MAX_ENTRIES) {
				// 만료된 것만으로 안 줄면 통째로 비운다. 조회가 잠깐 늘 뿐 틀린 값은 안 나간다
				tracking.clear();
			}
			log.info("배송조회 캐시 정리: {} → {}", before, tracking.size());
		}
		tracking.put(key, entry);
	}

	private static String key(String carrierCode, String trackingNo) {
		return carrierCode + ":" + trackingNo;
	}

	/**
	 * @param value     담아 둔 값. 실패를 담은 칸이면 null 이다
	 * @param expiresAt null 이면 만료되지 않는다 (배송완료)
	 */
	private record Entry<T>(T value, Instant expiresAt) {

		static <T> Entry<T> of(T value, Duration ttl) {
			return new Entry<>(value, (ttl == null) ? null : Instant.now().plus(ttl));
		}

		boolean expired() {
			return expiresAt != null && Instant.now().isAfter(expiresAt);
		}
	}
}
