package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.ShipmentRef;
import store.moeum.moeum.order.dto.TrackingResponse;
import store.moeum.moeum.order.infra.SmartTrackerClient;
import store.moeum.moeum.order.infra.TrackingCache;
import store.moeum.moeum.order.infra.TrackingException;

import java.util.List;
import java.util.Optional;

/**
 * 배송조회 (D-048).
 *
 * <b>{@code @Transactional} 이 없다.</b> 스마트택배 호출이 외부 호출이라 트랜잭션 안에
 * 두면 안 된다 (CLAUDE.md 규칙 1) — 읽기 전용이라도 그쪽이 5초 걸리면 커넥션을 5초 잡는다.
 * 필요한 값을 {@link ShipmentRef} 한 쿼리로 꺼내 오므로 열어 둘 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class TrackingService {

	/** 캐시된 실패에는 원래 사유가 없다. 화면에 보여 줄 문구만 준다 */
	private static final String UNAVAILABLE = "지금은 배송조회를 할 수 없습니다. 잠시 후 다시 시도해 주세요.";

	private final ShippingRepository shippingRepository;
	private final SmartTrackerClient smartTrackerClient;
	private final TrackingCache cache;

	/**
	 * 구매자의 배송조회.
	 *
	 * <b>조회가 실패해도 404 가 아니다.</b> 택배사가 아직 송장을 인식하지 못했거나
	 * (등록 직후에 흔하다) 그쪽 API 가 잠깐 죽은 것일 수 있다. 주문과 송장번호는 멀쩡하니
	 * 화면은 "조회할 수 없다" 를 띄우고 송장번호는 그대로 보여 줘야 한다 —
	 * 구매자가 택배사 사이트에서 직접 조회할 수 있다.
	 */
	public TrackingResponse track(String kakaoId, String orderToken) {
		ShipmentRef ref = shippingRepository.findRefByOrderToken(orderToken, kakaoId)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (!ref.trackable()) {
			// 송장은 있는데 택배사 코드가 없다. 조회 키가 없던 때 등록된 건이다
			return TrackingResponse.unavailable(ref, "이 주문은 배송조회를 제공하지 않습니다.");
		}

		// 캐시된 칸이 있으면 그것으로 끝낸다. 이용권이 월 100건이라 여기서 막는 것이 전제다
		Optional<Optional<SmartTrackerClient.Tracking>> cached =
				cache.get(ref.carrierCode(), ref.trackingNo());
		if (cached.isPresent()) {
			return cached.get()
					.map(tracking -> TrackingResponse.of(ref, tracking))
					.orElseGet(() -> TrackingResponse.unavailable(ref, UNAVAILABLE));
		}

		try {
			SmartTrackerClient.Tracking tracking =
					smartTrackerClient.track(ref.carrierCode(), ref.trackingNo());
			cache.put(ref.carrierCode(), ref.trackingNo(), tracking);
			return TrackingResponse.of(ref, tracking);

		} catch (TrackingException e) {
			// 실패도 담는다. 그쪽이 죽어 있으면 새로고침마다 이용권을 한 건씩 태운다
			cache.putFailure(ref.carrierCode(), ref.trackingNo());
			return TrackingResponse.unavailable(ref, e.getMessage());
		}
	}

	/**
	 * 송장 등록 화면의 택배사 선택지.
	 *
	 * <b>목록을 우리가 들고 있지 않는다.</b> 택배사는 늘고 코드는 그쪽이 정한다.
	 * 화면이 이 목록에서 고르게 하면 등록되는 코드가 항상 조회에 쓸 수 있는 코드다.
	 *
	 * 키가 없거나 그쪽이 죽으면 <b>빈 목록</b>을 준다. 여기서 예외를 던지면 송장 등록
	 * 화면 자체가 안 열린다 — 배송조회는 부가 기능이라 등록을 막으면 안 된다.
	 * 화면은 빈 목록이면 택배사를 직접 입력하게 두면 된다 (코드 없이 등록된다).
	 */
	public List<SmartTrackerClient.Carrier> carriers() {
		if (!smartTrackerClient.isConfigured()) {
			return List.of();
		}

		Optional<List<SmartTrackerClient.Carrier>> cached = cache.carriers();
		if (cached.isPresent()) {
			return cached.get();
		}

		try {
			List<SmartTrackerClient.Carrier> carriers = smartTrackerClient.carriers();
			cache.putCarriers(carriers);
			return carriers;

		} catch (TrackingException e) {
			// 실패는 담지 않는다. 목록이 비면 셀러가 택배사를 고를 수 없어 다음 시도는 열어 둔다
			return List.of();
		}
	}
}
