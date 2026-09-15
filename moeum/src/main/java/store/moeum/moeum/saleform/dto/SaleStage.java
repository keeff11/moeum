package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.saleform.domain.SaleType;

import java.util.List;

/**
 * 셀러 판매 상세의 진행 단계 (와이어프레임 S9 · D-058).
 *
 * <b>{@link store.moeum.moeum.order.dto.BuyerOrderStatus} 와 축이 다르다.</b> 구매자는
 * 자기 주문 하나가 어디까지 왔는지를 보지만, 셀러는 <b>판매 하나에 묶인 주문 전체</b>가
 * 어디까지 왔는지를 본다. 그래서 값이 결제·취소 없이 진행 단계만으로 되어 있다 —
 * 결제 대기나 실패는 주문 목록(G6)의 배지가 말한다.
 *
 * <b>이름은 {@link OrderStatus} 를 그대로 쓰지 않는다.</b> 내부 상태값은 CREATED · EXPIRED
 * 처럼 타임라인에 세울 수 없는 값을 품고 있어, 그대로 내보내면 프론트가 "이 값은 몇 번째
 * 칸인가" 를 스스로 판단해야 한다.
 */
@Schema(description = "판매 진행 단계. 공동구매는 RECRUITING → CLOSED → PRODUCING → "
		+ "ARRIVED → SHIPPED, 단독 판매는 PAID → ARRIVED → SHIPPED 다")
public enum SaleStage {

	/** 단독 판매의 첫 단계. 공동구매에는 모집이 있어서 여기 머무는 주문이 없다 */
	PAID("결제 완료"),

	RECRUITING("모집 중"),

	CLOSED("마감"),

	/**
	 * 발주 · 제작 중.
	 *
	 * <b>화면의 '발주' 와 '제작중' 두 칸이 이 한 단계다.</b> 셀러가 누르는 버튼이
	 * 하나(발주·제작 시작)이고 그 전이가 {@code CLOSED → PRODUCING} 하나뿐이라,
	 * 없는 구분을 API 가 지어내지 않는다 (D-049).
	 */
	PRODUCING("발주·제작 중"),

	/** 입고. 공동구매는 여기서 2차금 청구가 열린다 */
	ARRIVED("입고·2차금"),

	SHIPPED("발송");

	/** 공동구매 타임라인. 모집이 있고 발주를 거친다 */
	private static final List<SaleStage> GROUP_TIMELINE =
			List.of(RECRUITING, CLOSED, PRODUCING, ARRIVED, SHIPPED);

	/** 단독 판매 타임라인. 모집·발주가 없어 결제완료 → 준비중 → 발송이다 (domain.md 1절) */
	private static final List<SaleStage> SOLO_TIMELINE = List.of(PAID, ARRIVED, SHIPPED);

	private final String label;

	SaleStage(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/**
	 * 이 판매 유형이 밟는 단계들. 화면의 타임라인이 이 순서 그대로다.
	 *
	 * 유형에 따라 칸 수가 다른 이유는 단독 판매에 모집이라는 개념이 없기 때문이다 —
	 * 없는 칸을 회색으로 세워 두면 셀러는 영영 켜지지 않는 단계를 기다린다.
	 */
	public static List<SaleStage> timelineOf(SaleType saleType) {
		return saleType == SaleType.GROUP ? GROUP_TIMELINE : SOLO_TIMELINE;
	}

	/**
	 * 주문 상태 하나를 단계로 옮긴다.
	 *
	 * <b>공동구매의 PAID 는 모집 중으로 읽는다.</b> 결제 확정이 주문을 바로 RECRUITING
	 * 으로 올리므로(D-049) 정상 경로에는 없는 값이지만, 그 전이가 생기기 전에 결제된
	 * 옛 주문이 PAID 로 남아 있다. 그것을 버리면 타임라인의 합이 주문 수와 어긋난다.
	 *
	 * @return 타임라인에 세울 수 없는 상태(CREATED · EXPIRED · CANCELED)면 null
	 */
	public static SaleStage of(OrderStatus status, SaleType saleType) {
		return switch (status) {
			case PAID -> saleType == SaleType.GROUP ? RECRUITING : PAID;
			case RECRUITING -> RECRUITING;
			case CLOSED -> CLOSED;
			case PRODUCING -> PRODUCING;
			case ARRIVED -> ARRIVED;
			case SHIPPED -> SHIPPED;
			case CREATED, CANCELED, EXPIRED -> null;
		};
	}

	/**
	 * 단독 판매의 입고는 '배송 준비 중' 이다.
	 *
	 * 받을 잔금이 없어(D-046) 입고가 곧 보낼 수 있는 상태다. '입고·2차금' 이라고 쓰면
	 * 셀러는 오지 않을 2차금을 기다린다.
	 */
	public String labelOf(SaleType saleType) {
		if (this == ARRIVED && saleType == SaleType.SOLO) {
			return "배송 준비 중";
		}
		return label;
	}
}
