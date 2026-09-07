package store.moeum.moeum.payment.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 개별 취소 항목. 조회 응답의 {@code refunds[]} 이자 취소 요청의 200 응답이다.
 *
 * @param status completed · failed · processing.
 *               <b>POST 200 은 항상 completed 다</b> — 미확정이면 200 이 아니라 409 로 온다
 * @param fee    취소 처리 수수료. 부분 취소를 여러 번 하면 그만큼 여러 번 붙는다.
 *               셀러 정산에 영향이 있으므로 저장한다
 * @param failure 실패 원인. 예: {@code refundMethodDeclined}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Point3RefundEntry(
		String id,
		RefundEntryStatus status,
		Integer amount,
		Integer taxFreeAmount,
		Integer vat,
		Integer fee,
		String reason,
		Failure failure
) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Failure(String code, String message) {
	}

	public boolean isCompleted() {
		return status == RefundEntryStatus.COMPLETED;
	}

	public boolean isProcessing() {
		return status == RefundEntryStatus.PROCESSING;
	}
}
