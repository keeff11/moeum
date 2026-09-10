package store.moeum.moeum.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.SellerOrderCounts;
import store.moeum.moeum.order.dto.SellerOrderPageResponse.SellerOrderItem;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 셀러 홈 (와이어프레임 G1) — 처리할 주문 · 진행 중 판매 · 최근 주문.
 *
 * <b>화면 하나에 한 번만 부르라고 만든 응답이다.</b> 세 덩어리를 각각 부르게 두면
 * 홈을 열 때마다 왕복이 셋이고, 셋의 시점이 어긋나 배지 숫자와 카드가 서로 다른 순간을 가리킨다.
 *
 * <b>여기서 새로 세는 것은 없다.</b> 처리할 주문은 G6 탭 배지와 같은 집계이고
 * (D-033 · D-035), 최근 주문은 G6 목록의 첫 장이다. 홈과 목록이 다른 숫자를 보이면
 * 셀러는 어느 쪽을 믿어야 할지 모른다.
 */
@Schema(description = "셀러 홈 대시보드")
public record SellerHomeResponse(

		@Schema(description = "판매공간 이름. 헤더에 찍는다", example = "모음스토어")
		String storeName,

		@Schema(description = "지금 손을 대야 하는 주문 건수")
		TodoCounts todo,

		@Schema(description = "진행 중인 판매 카드. 마감이 임박한 것부터다. 비어 있으면 홈은 빈 상태다")
		List<ActiveSale> activeSales,

		@Schema(description = "최근 주문. G6 목록의 첫 장과 같은 카드다")
		List<SellerOrderItem> recentOrders,

		@Schema(description = "이 응답을 만든 시각. D-day 계산의 기준이다", example = "2026-09-10T17:20:00")
		LocalDateTime fetchedAt
) {

	/**
	 * "처리할 주문" 칸.
	 *
	 * <b>발송 완료는 세지 않는다.</b> 셀러가 할 일이 남은 것만 여기 온다 —
	 * 끝난 건수는 홈이 아니라 목록에서 본다.
	 */
	@Schema(description = "처리할 주문 건수")
	public record TodoCounts(

			@Schema(description = "2차금 미납 — 전 폼이 입고돼 지금 청구할 수 있는 건. "
					+ "S10 일괄 청구의 대상 건수와 같다 (DUE-110)", example = "16")
			long secondUnpaid,

			@Schema(description = "발송 대기 — 2차금까지 받고 아직 안 보낸 건", example = "1")
			long shippingWaiting,

			@Schema(description = "둘의 합. 배지 하나로 보여 줄 때 쓴다", example = "17")
			long total
	) {
	}

	/**
	 * 진행 중 판매 카드 한 장.
	 *
	 * <b>모집 숫자는 셀러에게 늘 보인다.</b> 구매자 화면은 {@code progressPublic} 이 꺼져 있으면
	 * 감추지만(ProductAvailabilityResponse), 그건 남에게 감추는 설정이지 주인에게 감추는 것이 아니다.
	 */
	@Schema(description = "진행 중 판매 카드")
	public record ActiveSale(

			@Schema(description = "판매 폼 id. 카드를 누르면 이 값으로 G5-O 로 간다", example = "12")
			Long saleFormId,

			@Schema(description = "판매 제목", example = "아크릴 스탠드 — 2차 공구")
			String title,

			@Schema(description = "SELLING 이거나 PAUSED 다. 일시중지도 진행 중으로 본다")
			SaleFormStatus status,

			@Schema(description = "공동구매인지 단독 판매인지")
			SaleType saleType,

			@Schema(description = "모집 68/100 의 68. 결제가 끝난 수량만 센다 — 결제 중인 선점분은 빼고 준다",
					example = "68")
			int recruitedCount,

			@Schema(description = "모집 68/100 의 100. 목표 수량을 두지 않았거나 단독 판매면 null",
					example = "100")
			Integer recruitTarget,

			@Schema(description = "마감 시각. 단독 판매는 없을 수 있다", example = "2026-09-15T23:59:59")
			LocalDateTime closesAt,

			@Schema(description = "마감까지 남은 날. D-5 의 5다. 오늘 마감이면 0, 마감일이 없으면 null. "
					+ "이미 지났으면 음수 — 마감 배치가 아직 안 돈 잠깐 사이다", example = "5")
			Integer dDay,

			@Schema(description = "목표 수량을 채웠는가. 못 채운 채 마감되면 shortfallPolicy 를 탄다 (D-026)",
					example = "false")
			boolean targetReached
	) {
	}

	// ---------------------------------------------------------------- 조립

	public static TodoCounts todoOf(SellerOrderCounts counts) {
		return new TodoCounts(counts.secondUnpaid(), counts.preparing(),
				counts.secondUnpaid() + counts.preparing());
	}

	public static ActiveSale saleOf(SaleForm form, LocalDateTime now) {
		Integer target = form.getTargetQty();

		return new ActiveSale(
				form.getId(),
				form.getTitle(),
				form.getStatus(),
				form.getSaleType(),
				form.getSold(),
				target,
				form.getClosesAt(),
				dDayOf(form.getClosesAt(), now),
				target != null && form.getSold() >= target);
	}

	/**
	 * D-day.
	 *
	 * <b>시각이 아니라 날짜로 센다.</b> 시간 차로 나누면 오늘 밤 11시 마감이 D-0 이 아니라
	 * D-1 로 보이는 일이 생긴다 — 화면의 D-5 는 달력 상의 날 수다.
	 */
	private static Integer dDayOf(LocalDateTime closesAt, LocalDateTime now) {
		if (closesAt == null) {
			return null;
		}
		return (int) ChronoUnit.DAYS.between(LocalDate.from(now), closesAt.toLocalDate());
	}
}
