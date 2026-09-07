package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 결제 세션 생성 요청 (point3-api 2절).
 *
 * <b>가맹점 주문번호를 넣을 필드가 없다.</b> 주문 ↔ sessionId 매핑은 전적으로 우리 DB 책임이다.
 * 저장에 실패하면 결제는 진행되는데 어느 주문인지 알 수 없게 된다.
 *
 * vat 를 비우면 point3 가 계산한다: {@code vat = round((amount - taxFree) / 11)}.
 * 우리가 직접 넣지 않는 이유는 반올림 규칙이 어긋나면 불변식
 * ({@code amount = supplyAmount + taxFreeAmount + vat})이 깨지기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Point3SessionRequest(
		int amount,
		String productName,
		String displayMerchantName,
		Integer taxFreeAmount,
		Integer vat,
		String tradeOpt
) {

	/** 일반 과세 상품. 면세·도서문화·대중교통이 필요해지면 그때 분기한다 */
	public static Point3SessionRequest general(int amount, String productName, String merchantName) {
		return new Point3SessionRequest(amount, productName, merchantName, null, null, "GENERAL");
	}
}
