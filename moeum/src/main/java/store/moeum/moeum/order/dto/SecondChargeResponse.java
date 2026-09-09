package store.moeum.moeum.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 2차금 일괄 청구 (와이어프레임 S10) — 미리보기와 실행 결과.
 *
 * <b>청구는 알림을 다시 내보내는 것이지 출금이 아니다.</b> 자동출금이 없어서
 * 실제 결제는 구매자가 링크로 들어와 승인해야 한다 — 이 응답의 금액은 "받을 돈" 이지
 * "받은 돈" 이 아니다.
 */
public final class SecondChargeResponse {

	private SecondChargeResponse() {
	}

	@Schema(description = "청구 대상 미리보기")
	public record Preview(

			@Schema(description = "지금 청구할 수 있는 주문 수. 셀러 주문 목록의 '2차금 미납' 탭과 같은 기준이다",
					example = "92")
			int targetCount,

			@Schema(description = "그중 실제로 이번에 나갈 건수. 최근 24시간 안에 이미 청구한 건은 빠진다",
					example = "80")
			int chargeableCount,

			@Schema(description = "대상 전체의 2차금 합계. 취소된 폼의 잔금은 빠져 있다", example = "368000")
			long totalAmount,

			@Schema(description = "가장 최근에 청구한 시각. 한 번도 청구한 적이 없으면 null",
					example = "2026-09-08T10:12:03")
			LocalDateTime lastChargedAt
	) {
	}

	@Schema(description = "청구 실행 결과")
	public record Result(

			@Schema(description = "이번에 알림을 다시 내보낸 주문 수", example = "80")
			int chargedCount,

			@Schema(description = "최근 24시간 안에 이미 청구해서 건너뛴 주문 수", example = "12")
			int skippedCount,

			@Schema(description = "이번에 청구한 금액 합계", example = "320000")
			long chargedAmount
	) {
	}
}
