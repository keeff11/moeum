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
 *  - 상품 · 옵션 : 주문이 걸린 뒤 금액이 바뀌면 주문 시점 스냅샷과 어긋난다. 별도 작업으로 다룬다
 */
public record SaleFormUpdate(
		String title,
		int stockMax,
		Integer targetQty,
		Integer maxPerUser,
		LocalDateTime opensAt,
		LocalDateTime closesAt,
		ShortfallPolicy shortfallPolicy,
		String shipStartText,
		int minOrderAmount,
		String descriptionJson,
		Boolean progressPublic,

		/** 노출 순서대로의 이미지 URL 목록. 통째로 교체된다 */
		List<String> images
) {
}
