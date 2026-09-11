package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.csv.CsvWriter;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.order.dto.PurchaseOrderLine;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 발주서 — 공장에 "무엇을 몇 개 만들라" 고 보내는 파일 (domain.md 5절 ORD).
 *
 * <b>구매자 정보는 한 글자도 담기지 않는다.</b> 공장은 누가 샀는지 알 필요가 없다.
 * 셀러가 배송대행을 맡기려면 수취인 · 주소 · 연락처가 필요해지는데 그건 발주서가 아니라
 * <b>송장 목록</b>이고, 개인정보 덩어리라 파일부터 갈라 둔다. 한 파일에 섞으면 나중에 못 나눈다.
 *
 * 와이어프레임에 화면이 없다. 컬럼과 파일 이름은 여기서 정한 것이다.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final OrderRepository orderRepository;
	private final SaleFormRepository saleFormRepository;
	private final SellerService sellerService;

	/**
	 * 발주서를 만든다.
	 *
	 * <b>마감 전에도 받을 수 있다.</b> 막으면 셀러가 화면 숫자를 손으로 옮겨 적는다 —
	 * 발주량을 미리 가늠하려는 수요는 실제로 있다. 대신 아직 확정이 아니라는 것을
	 * 파일 이름에 박는다 ({@link #isProvisional}).
	 */
	@Transactional(readOnly = true)
	public PurchaseOrderFile create(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = ownedForm(seller, saleFormId);

		List<PurchaseOrderLine> lines = orderRepository.findPurchaseOrderLines(saleFormId);
		boolean provisional = isProvisional(form);

		CsvWriter csv = new CsvWriter()
				.row("상품명", "옵션명", "주문 수량", "취소 수량", "발주 수량");
		for (PurchaseOrderLine line : lines) {
			csv.row(line.productName(), line.optionName(),
					line.orderedQty(), line.canceledQty(), line.netQty());
		}

		return new PurchaseOrderFile(fileNameOf(form, provisional), csv.toBytes(), provisional);
	}

	/**
	 * 없는 폼과 남의 폼을 똑같이 404 로 돌려준다.
	 * 403 이면 "그 id 에 폼이 있긴 하다" 가 새어 나가 남의 판매를 훑을 수 있다
	 * ({@code SellerOrderService#requireOwnedForm} 과 같은 이유다).
	 */
	private SaleForm ownedForm(Seller seller, Long saleFormId) {
		SaleForm form = saleFormRepository.findById(saleFormId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		if (!form.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
		return form;
	}

	/**
	 * 아직 숫자가 움직일 수 있는가.
	 *
	 * 마감 전이면 당연히 움직인다. <b>마감 직후도 마찬가지다</b> — 목표수량 미달
	 * 자동취소(D-026)가 아직 안 돌았으면 곧 취소될 주문이 발주서에 들어 있다.
	 * 배치는 1분마다 돌지만 셀러가 마감 시각에 화면을 보고 있으면 충분히 걸린다.
	 *
	 * SOLO 는 목표수량이라는 개념이 없어 미달 처리를 기다리지 않는다.
	 */
	private static boolean isProvisional(SaleForm form) {
		boolean closed = form.getStatus() == SaleFormStatus.CLOSED
				|| form.getStatus() == SaleFormStatus.ENDED;
		if (!closed) {
			return true;
		}
		return form.getSaleType() == SaleType.GROUP && form.getShortfallDoneAt() == null;
	}

	/** 발주서_{판매명}_{날짜}.csv. 확정 전이면 뒤에 _잠정 이 붙는다 */
	private static String fileNameOf(SaleForm form, boolean provisional) {
		return "발주서_%s_%s%s.csv".formatted(
				safeTitle(form.getTitle()),
				LocalDate.now(KST).format(DATE),
				provisional ? "_잠정" : "");
	}

	/**
	 * 판매 제목을 파일 이름에 쓸 수 있게 다듬는다.
	 *
	 * 셀러가 정한 자유 텍스트라 경로 구분자나 제어문자가 들어올 수 있다.
	 * 윈도우가 파일 이름에 금지하는 글자까지 한 번에 걷어 낸다.
	 */
	private static String safeTitle(String title) {
		String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
		if (cleaned.isEmpty()) {
			return "판매";
		}
		return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
	}

	/**
	 * @param fileName    Content-Disposition 에 실을 이름. 한글이라 인코딩이 필요하다
	 * @param content     BOM 이 붙은 UTF-8 CSV
	 * @param provisional 아직 숫자가 움직일 수 있는가
	 */
	public record PurchaseOrderFile(String fileName, byte[] content, boolean provisional) {
	}
}
