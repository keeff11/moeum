package store.moeum.moeum.saleform.dto;

import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;

import java.time.LocalDateTime;

/**
 * 모집 현황 · 재고 (api-spec 2절). <b>휘발성이라 캐시하지 않는다 (no-store).</b>
 *
 * 정적 정보와 나눠 둔 이유가 이것이다. 상품 페이지는 캐시해서 빨리 띄우고,
 * 초 단위로 바뀌는 재고만 매번 새로 받아 간다.
 */
public record ProductAvailabilityResponse(

		/**
		 * 모집 68/100 의 분자. <b>확정 주문 수량이다 — 홀드는 포함하지 않는다.</b>
		 * 결제가 끝나지 않은 선점분까지 세면, 15분 뒤 만료됐을 때 숫자가 뒤로 간다.
		 * 분모인 targetQty 가 수량이라 여기도 수량으로 맞춘다.
		 *
		 * GROUP 이 아니거나 셀러가 진행 현황을 감췄으면 null.
		 */
		Integer recruitedCount,

		/** 68/100 의 분모. 감췄으면 null */
		Integer recruitTarget,

		/** 살 수 있는 수량. stockMax - held - sold */
		int stock,

		PublicStatus status,

		/** 이 응답을 만든 시각. 프론트가 initialDataUpdatedAt 으로 쓴다 */
		LocalDateTime fetchedAt
) {

	public static ProductAvailabilityResponse of(SaleForm form, LocalDateTime now) {
		boolean showProgress = (form.getSaleType() == SaleType.GROUP) && form.isProgressPublic();

		return new ProductAvailabilityResponse(
				showProgress ? form.getSold() : null,
				showProgress ? form.getTargetQty() : null,
				Math.max(form.remainingStock(), 0),
				PublicStatus.of(form, now),
				now
		);
	}
}
