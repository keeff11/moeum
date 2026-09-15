package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.order.domain.SaleFormStageCounts;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 판매 진행 현황 (와이어프레임 S9 · D-058).
 *
 * <b>셀러가 발주·입고를 누른 뒤 보는 값이다.</b> 전이 API 는 "몇 건 넘어갔다" 만 주고,
 * 판매 폼 상세({@link SaleFormDetailResponse})는 폼의 상태·재고만 말한다 — 진행 단계는
 * 주문에 있어서 (D-049) 어느 쪽에도 없었다.
 *
 * <b>단계 숫자는 주문 수다.</b> 재고 수량(sold)이 아니다 — 한 주문이 3개를 사도 단계는
 * 한 번만 움직인다. 수량은 상세와 발주서(D-045)가 말한다.
 *
 * <b>발주와 제작 중은 같은 전이의 두 칸이라 건수가 같다</b> ({@link SaleStage#ORDERED}).
 * 그래서 <b>칸 건수를 더하지 않는다</b> — 전체 주문 수는 {@code totalOrders} 다.
 *
 * <b>2차금 청구 대상 건수는 여기서 세지 않는다.</b> 그것은 묶음 단위이고 이미 주문 목록의
 * 탭 배지({@code countSellerOrderTabs})가 판매 폼별로 세고 있다 — 같은 숫자를 두 군데서
 * 따로 세면 갈라지고, 그때 셀러는 어느 쪽을 믿을지 모른다 (D-038).
 */
@Schema(description = "판매 진행 현황. 단계 타임라인과 단계별 주문 수")
public record SaleFormProgressResponse(

		@Schema(description = "판매 폼 id", example = "12")
		Long saleFormId,

		@Schema(description = "상품명", example = "아크릴 스탠드")
		String title,

		@Schema(description = "판매 유형. GROUP=공동구매, SOLO=단독 판매")
		SaleType saleType,

		@Schema(description = "판매 폼 상태. 진행 단계와 다른 축이다 — 폼이 마감(CLOSED)돼도 "
				+ "주문은 제작 중일 수 있다")
		SaleFormStatus formStatus,

		@Schema(description = "지금 단계. 살아 있는 주문 중 <b>가장 덜 진행된</b> 것의 단계다 — "
				+ "한 건이라도 남아 있으면 판매가 그 단계를 벗어난 것이 아니다. "
				+ "ORDERED(발주)는 여기 오지 않는다 — 제작 중과 같은 전이의 표시용 칸이다")
		SaleStage stage,

		@Schema(description = "지금 단계의 표시 문구", example = "마감")
		String stageLabel,

		@Schema(description = "타임라인. 이 판매 유형이 밟는 단계가 화면의 스트립 순서 그대로 온다. "
				+ "공동구매 여섯 칸, 단독 판매 세 칸")
		List<StageStep> stages,

		@Schema(description = "살아 있는 주문 수. 결제 전·만료·취소는 빠진다", example = "8")
		int totalOrders,

		@Schema(description = "취소된 주문 수. 발주 수량과 대조하라고 같이 준다", example = "1")
		int canceledOrders,

		@Schema(description = "지금 '발주·제작 시작' 이 넘길 주문 수. 0이면 버튼을 끈다 — "
				+ "모집이 마감돼야 값이 생긴다", example = "8")
		int producibleOrders,

		@Schema(description = "지금 '입고 처리' 가 넘길 주문 수. 0이면 버튼을 끈다", example = "8")
		int arrivableOrders,

		@Schema(description = "모집 마감 시각. 단독 판매면 null")
		LocalDateTime closesAt,

		@Schema(description = "이 숫자를 센 시각")
		LocalDateTime countedAt
) {

	/** 타임라인의 칸 하나 */
	@Schema(description = "타임라인의 단계 한 칸")
	public record StageStep(

			@Schema(description = "단계")
			SaleStage stage,

			@Schema(description = "표시 문구. 화면의 단계 스트립에 찍는 말 그대로다", example = "제작중")
			String label,

			@Schema(description = "지금 이 단계에 서 있는 주문 수. <b>발주 칸은 제작중 칸과 같은 "
					+ "값이다</b>(같은 전이의 두 칸) — 칸 건수를 더하지 말고 totalOrders 를 쓴다",
					example = "8")
			int orders,

			@Schema(description = "여기까지 왔는가. 지금 단계이거나 그보다 앞이면 true",
					example = "true")
			boolean reached,

			@Schema(description = "지금 단계인가", example = "false")
			boolean current) {
	}

	public static SaleFormProgressResponse of(SaleForm form, SaleFormStageCounts counts,
	                                          LocalDateTime countedAt) {
		SaleType saleType = form.getSaleType();
		List<SaleStage> timeline = SaleStage.timelineOf(saleType);
		// 주문이 실제로 서 있는 단계. 발주 칸은 여기에 없다 — 현재 단계를 고를 때
		// 제작 중과 같은 건수로 걸려 늘 발주가 뽑힌다
		Map<SaleStage, Integer> standing = ordersByStage(counts, saleType);
		SaleStage stage = currentStage(form, timeline, standing);

		return new SaleFormProgressResponse(
				form.getId(),
				form.getTitle(),
				saleType,
				form.getStatus(),
				stage,
				stage.labelOf(saleType),
				steps(timeline, standing, stage, saleType),
				(int) counts.live(),
				(int) counts.canceled(),
				(int) counts.closed(),
				(int) counts.arrivable(),
				form.getClosesAt(),
				countedAt);
	}

	private static List<StageStep> steps(List<SaleStage> timeline, Map<SaleStage, Integer> standing,
	                                     SaleStage stage, SaleType saleType) {
		int currentIndex = timeline.indexOf(stage);
		List<StageStep> steps = new ArrayList<>(timeline.size());

		for (int i = 0; i < timeline.size(); i++) {
			SaleStage step = timeline.get(i);
			steps.add(new StageStep(
					step,
					step.labelOf(saleType),
					ordersAt(step, standing),
					i <= currentIndex,
					i == currentIndex));
		}
		return steps;
	}

	/**
	 * 칸에 찍을 건수.
	 *
	 * <b>발주 칸은 제작 중 칸의 건수를 그대로 비춘다.</b> 화면은 발주와 제작 중이 두
	 * 칸이지만 셀러가 누르는 버튼은 하나(발주·제작 시작)고 주문 상태도 PRODUCING 하나다
	 * (D-049). 0으로 두면 <b>지나온 칸에 0건이 찍혀</b> 셀러는 발주가 빠진 줄로 읽는다.
	 */
	private static int ordersAt(SaleStage step, Map<SaleStage, Integer> standing) {
		SaleStage source = step == SaleStage.ORDERED ? SaleStage.PRODUCING : step;
		return standing.getOrDefault(source, 0);
	}

	/**
	 * 지금 단계 — 살아 있는 주문 중 <b>가장 덜 진행된</b> 것이다.
	 *
	 * 가장 앞선 것으로 잡으면 한 건만 발송해도 타임라인이 '발송' 으로 뛴다. 셀러가 봐야
	 * 하는 것은 아직 처리하지 않은 쪽이라 구매자 배지와 같은 규칙을 쓴다
	 * ({@code BuyerOrderStatus#progressOf}).
	 *
	 * <b>주문이 한 건도 없으면 폼 상태에서 읽는다.</b> 아직 아무도 안 샀어도 판매는
	 * 모집 중이고, 마감한 폼은 마감이다 — 그때 타임라인이 비어 있으면 셀러는 자기 판매가
	 * 어느 단계인지 볼 곳이 없다.
	 */
	private static SaleStage currentStage(SaleForm form, List<SaleStage> timeline,
	                                      Map<SaleStage, Integer> standing) {
		for (SaleStage stage : timeline) {
			// 발주 칸에는 주문이 서지 않는다 — standing 에 없어 그대로 지나간다
			if (standing.getOrDefault(stage, 0) > 0) {
				return stage;
			}
		}
		if (form.getSaleType() == SaleType.SOLO) {
			return timeline.get(0);
		}
		return switch (form.getStatus()) {
			case CLOSED, ENDED -> SaleStage.CLOSED;
			case DRAFT, SELLING, PAUSED -> SaleStage.RECRUITING;
		};
	}

	/**
	 * 상태별 주문 수를 단계별로 접는다.
	 *
	 * 타임라인에 없는 단계로 접히는 값은 버린다 — 단독 판매의 모집·발주처럼 그 유형이
	 * 밟지 않는 상태라 정상 경로에서는 나올 수 없다.
	 */
	private static Map<SaleStage, Integer> ordersByStage(SaleFormStageCounts counts,
	                                                     SaleType saleType) {
		Map<SaleStage, Integer> orders = new EnumMap<>(SaleStage.class);
		add(orders, OrderStatus.PAID, counts.paid(), saleType);
		add(orders, OrderStatus.RECRUITING, counts.recruiting(), saleType);
		add(orders, OrderStatus.CLOSED, counts.closed(), saleType);
		add(orders, OrderStatus.PRODUCING, counts.producing(), saleType);
		add(orders, OrderStatus.ARRIVED, counts.arrived(), saleType);
		add(orders, OrderStatus.SHIPPED, counts.shipped(), saleType);
		return orders;
	}

	private static void add(Map<SaleStage, Integer> orders, OrderStatus status, long count,
	                        SaleType saleType) {
		SaleStage stage = SaleStage.of(status, saleType);
		if (stage == null || count == 0) {
			return;
		}
		orders.merge(stage, (int) count, Integer::sum);
	}
}
