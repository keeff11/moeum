package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.infra.SmartTrackerClient;

import java.util.List;

/**
 * 배송조회 결과 (D-048).
 *
 * <b>조회에 실패해도 이 응답이 나간다.</b> 택배사가 아직 송장을 인식하지 못했거나
 * 그쪽 API 가 잠깐 죽은 것일 수 있는데, 그때 404 를 주면 화면이 송장번호까지 잃는다 —
 * 구매자가 택배사 사이트에서 직접 조회할 길을 막는 셈이다.
 *
 * @param carrier    택배사 이름. <b>항상 채워진다</b>
 * @param trackingNo 송장번호. <b>항상 채워진다</b>
 * @param available  조회에 성공했는가. false 면 아래 단계 정보가 비어 있다
 * @param message    조회하지 못한 이유. 성공이면 null
 * @param level      진행 단계 1~6. 조회 실패면 0
 * @param completed  배송이 끝났는가
 * @param steps      단계별 기록. 오래된 것부터다
 */
@Schema(description = "배송조회 결과")
public record TrackingResponse(

		@Schema(description = "택배사 이름. 조회에 실패해도 채워진다", example = "CJ대한통운")
		String carrier,

		@Schema(description = "송장번호. 조회에 실패해도 채워진다", example = "123456789012")
		String trackingNo,

		@Schema(description = "조회에 성공했는가. false 면 택배사 사이트에서 직접 조회하도록 안내한다",
				example = "true")
		boolean available,

		@Schema(description = "조회하지 못한 이유. 성공이면 null", example = "운송장 번호가 등록되지 않았습니다.")
		String message,

		@Schema(description = "택배사가 준 진행 단계 번호. 클수록 배송이 진행된 것이고 6 이 완료다. "
				+ "각 번호의 정확한 이름은 공식 명세에 없으므로, 화면 문구는 steps[].kind 를 쓴다. "
				+ "조회 실패면 0", example = "6")
		int level,

		@Schema(description = "배송이 끝났는가", example = "true")
		boolean completed,

		@Schema(description = "단계별 기록. 오래된 것부터다")
		List<Step> steps
) {

	@Schema(description = "배송 단계 하나")
	public record Step(

			@Schema(description = "택배사가 준 시각 문자열. 형식이 택배사마다 달라 그대로 보여 준다",
					example = "2026-09-12 14:03:00")
			String time,

			@Schema(description = "어디에서", example = "서울강남")
			String where,

			@Schema(description = "무엇을 했는지. 택배사 표현 그대로다", example = "배송완료")
			String kind,

			@Schema(description = "그 시점의 진행 단계", example = "6")
			int level) {
	}

	public static TrackingResponse of(ShipmentRef ref, SmartTrackerClient.Tracking tracking) {
		List<Step> steps = tracking.steps().stream()
				.map(step -> new Step(step.time(), step.where(), step.kind(), step.level()))
				.toList();

		return new TrackingResponse(ref.carrier(), ref.trackingNo(), true, null,
				tracking.level(), tracking.completed(), steps);
	}

	/** 조회는 못 했지만 송장번호는 준다 */
	public static TrackingResponse unavailable(ShipmentRef ref, String message) {
		return new TrackingResponse(ref.carrier(), ref.trackingNo(), false, message,
				0, false, List.of());
	}
}
