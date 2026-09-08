package store.moeum.moeum.saleform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;

import java.time.LocalDateTime;

/**
 * 구매자에게 내보내는 상태. DB 의 {@link SaleFormStatus} 를 그대로 쓰지 않는다.
 *
 * 두 가지가 다르다.
 *  - DRAFT 는 아예 없다. 미발행 폼은 404 로 숨긴다 (서비스가 처리한다)
 *  - SOLD_OUT 은 저장되는 상태가 아니라 재고에서 파생된다
 *
 * 마감 판정을 조회 시점에도 한 번 더 한다. 마감 배치가 1분마다 돌기 때문에
 * closes_at 이 막 지난 폼은 아직 DB 상 SELLING 일 수 있다.
 * 그 틈에 구매 버튼이 켜져 보이면 홀드까지 시도했다가 실패한다 —
 * 재고 확보 쿼리는 closes_at 을 이미 보고 있으므로 여기서 미리 잘라 주는 편이 낫다.
 */
@Schema(description = """
		구매자에게 보이는 판매 상태.
		SELLING=판매 중(구매 버튼 활성) · SOLD_OUT=재고 소진 · CLOSED=마감 · PAUSED=셀러가 일시중지""")
public enum PublicStatus {

	/** 살 수 있다. 구매 버튼을 켠다 */
	SELLING,
	/** 재고가 없다 */
	SOLD_OUT,
	/** 마감됐다. 마감 시각이 막 지난 경우도 여기로 온다 */
	CLOSED,
	/** 셀러가 잠시 멈췄다 */
	PAUSED;

	public static PublicStatus of(SaleForm form, LocalDateTime now) {
		if (form.getStatus() == SaleFormStatus.PAUSED) {
			return PAUSED;
		}
		if (form.getStatus() != SaleFormStatus.SELLING) {
			// CLOSED · ENDED — 구매자에게는 둘 다 '마감'이다. DRAFT 는 여기 오지 않는다
			return CLOSED;
		}
		if (form.getClosesAt() != null && !form.getClosesAt().isAfter(now)) {
			return CLOSED;
		}
		if (form.remainingStock() <= 0) {
			return SOLD_OUT;
		}
		return SELLING;
	}
}
