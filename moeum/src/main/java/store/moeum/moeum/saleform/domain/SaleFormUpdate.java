package store.moeum.moeum.saleform.domain;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 판매 폼 수정 명령. 여기 없는 필드는 고칠 수 없다.
 *
 * 뺀 것과 이유
 *  - slug      : "링크 하나 = 폼 하나"다. 이미 배포된 링크가 깨진다
 *  - saleType  : GROUP 과 SOLO 는 목표수량 · 2차금 규칙이 달라 사실상 다른 상품이다
 *  - status    : 상태 전이는 별도 흐름(판매 시작 · 일시중지 · 마감)이다
 *  - held/sold : DB 가 주인이다
 *  - 상품 · 옵션 : 주문이 걸린 뒤 금액이 바뀌면 주문 시점 스냅샷과 어긋난다. 별도 작업으로 다룬다.
 *                 옵션 재고만 예외로 따로 고친다 (SaleFormService.updateOptionStock, D-054)
 */
public record SaleFormUpdate(
		String title,

		/**
		 * 판매할 총 수량. 옵션 재고 모드인 폼은 옵션 합계가 쓰이므로 이 값을 보지 않는다.
		 * 폼 재고 모드에서는 필수다 — 서비스가 검사한다
		 */
		Integer stockMax,
		Integer targetQty,
		Integer maxPerUser,
		LocalDateTime opensAt,
		LocalDateTime closesAt,
		ShortfallPolicy shortfallPolicy,
		String shipStartText,
		int minOrderAmount,

		/** 폼별 배송비. null 이면 셀러 기본 배송비를 따른다 (D-053) */
		Integer shippingFee,
		String descriptionJson,
		Boolean progressPublic,

		/** 노출 순서대로의 이미지 URL 목록. 통째로 교체된다 */
		List<String> images
) {
}
