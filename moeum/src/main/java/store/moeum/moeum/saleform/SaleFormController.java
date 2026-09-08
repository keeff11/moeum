package store.moeum.moeum.saleform;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.saleform.dto.ImageUploadUrlRequest;
import store.moeum.moeum.saleform.dto.ImageUploadUrlResponse;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.saleform.dto.SaleFormHistoryResponse;
import store.moeum.moeum.saleform.dto.SaleFormUpdateRequest;
import store.moeum.moeum.saleform.dto.SaleFormSummaryResponse;

import java.net.URI;
import java.util.List;

@Tag(name = "판매 폼", description = "생성 · 조회 · 수정 · 변경 이력")
@RestController
@RequestMapping("/seller/sale-forms")
@RequiredArgsConstructor
public class SaleFormController {

	private final SaleFormService saleFormService;

	@Operation(summary = "판매 폼 만들기",
			description = """
					상품과 옵션까지 한 번에 만든다. 만들면 작성 중(DRAFT) 상태이고 구매자에게 보이지 않는다 —
					판매 시작을 눌러야 공개된다.

					심사가 승인된 셀러만 만들 수 있다.
					""")
	@PostMapping
	public ResponseEntity<SaleFormDetailResponse> create(@LoginUser SessionUser user,
	                                                     @Valid @RequestBody SaleFormCreateRequest request) {
		Long id = saleFormService.create(user.kakaoId(), request);
		return ResponseEntity.created(URI.create("/seller/sale-forms/" + id))
				.body(saleFormService.findMineDetail(user.kakaoId(), id));
	}

	/**
	 * 이미지 업로드 URL 발급. 파일 자체는 이 서버를 지나가지 않는다 (D-022).
	 * 프론트가 받은 URL 로 S3 에 직접 PUT 하고, 결과 objectKey 를 폼 저장 때 실어 보낸다.
	 */
	@Operation(summary = "이미지 업로드 URL 발급",
			description = """
					브라우저가 S3 에 직접 올릴 주소를 발급한다. 이미지 파일이 서버를 거치지 않는다.

					1. 이 API 로 uploadUrl 과 objectKey 를 받는다
					2. uploadUrl 로 파일을 PUT 한다 (Content-Type 을 응답의 contentType 과 똑같이 넣는다)
					3. 판매 폼이나 셀러 프로필 저장 시 objectKey 를 넘긴다

					★ contentLength 가 실제 파일 크기와 다르면 업로드가 거부된다.
					""")
	@PostMapping("/images/upload-url")
	public ImageUploadUrlResponse imageUploadUrl(@LoginUser SessionUser user,
	                                             @Valid @RequestBody ImageUploadUrlRequest request) {
		return saleFormService.issueImageUploadUrl(user.kakaoId(), request);
	}

	@Operation(summary = "내 판매 폼 목록",
			description = "작성 중인 것까지 전부 준다. 구매자에게 보이는 목록과는 다르다.")
	@GetMapping
	public List<SaleFormSummaryResponse> list(@LoginUser SessionUser user) {
		return saleFormService.findMine(user.kakaoId());
	}

	@Operation(summary = "판매 폼 상세 (셀러용)",
			description = "재고·홀드 수량처럼 셀러만 볼 값이 함께 나온다.")
	@GetMapping("/{saleFormId}")
	public SaleFormDetailResponse detail(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return saleFormService.findMineDetail(user.kakaoId(), saleFormId);
	}

	/** 전체 교체(PUT). 보내지 않은 선택 필드는 비워진다 */
	@Operation(summary = "판매 폼 수정",
			description = """
					전체 폼을 보내는 방식이다. 바뀐 항목만 sale_form_history 에 기록된다.

					이미 팔린 수량보다 재고를 적게 줄일 수 없다.
					""")
	@PutMapping("/{saleFormId}")
	public SaleFormDetailResponse update(@LoginUser SessionUser user,
	                                     @Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId,
	                                     @Valid @RequestBody SaleFormUpdateRequest request) {
		return saleFormService.update(user.kakaoId(), saleFormId, request.toCommand());
	}

	/**
	 * 판매 시작. 생성 직후는 DRAFT 라 이걸 불러야 구매자에게 보인다.
	 * 일시중지한 폼을 다시 여는 데도 같은 API 를 쓴다.
	 */
	@Operation(summary = "판매 시작",
			description = "이때부터 구매자에게 공개된다. 마감된 폼은 다시 열 수 없다.")
	@PostMapping("/{saleFormId}/start")
	public SaleFormDetailResponse start(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return saleFormService.startSelling(user.kakaoId(), saleFormId);
	}

	/** 일시중지. 구매 버튼만 막고 이미 잡힌 홀드·결제는 그대로 흘러간다 */
	@Operation(summary = "판매 일시중지",
			description = "구매 버튼이 꺼진다. 다시 시작할 수 있다.")
	@PostMapping("/{saleFormId}/pause")
	public SaleFormDetailResponse pause(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return saleFormService.pause(user.kakaoId(), saleFormId);
	}

	/** 수동 마감. <b>되돌릴 수 없다</b> — 다시 팔려면 새 폼을 만들어야 한다 */
	@Operation(summary = "판매 마감",
			description = "★ 되돌릴 수 없다. 마감 후에는 다시 판매를 시작할 수 없다.")
	@PostMapping("/{saleFormId}/close")
	public SaleFormDetailResponse close(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return saleFormService.close(user.kakaoId(), saleFormId);
	}

	/**
	 * 입고 처리 (5단계 시작점). 이 폼의 결제 완료 주문을 입고 상태로 넘긴다.
	 * 묶음의 모든 폼이 입고되면 구매자에게 2차금 청구가 열린다.
	 */
	@Operation(summary = "입고 처리",
			description = """
					이 폼의 결제 완료 주문을 입고 상태로 넘긴다. 응답은 처리된 주문 수다.

					묶음의 모든 폼이 입고돼야 그 구매자의 2차금 청구가 열리고, 그때 알림이 나간다.
					""")
	@PostMapping("/{saleFormId}/arrive")
	public ArrivedResponse arrive(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return new ArrivedResponse(saleFormService.markArrived(user.kakaoId(), saleFormId));
	}

	/** @param arrivedOrders 이번에 입고 처리된 주문 수 */
	@Schema(description = "입고 처리 결과")
	public record ArrivedResponse(
			@Schema(description = "이번에 입고 상태로 넘어간 주문 수. 이미 입고된 건은 세지 않는다",
					example = "3")
			int arrivedOrders) {
	}

	@Operation(summary = "판매 폼 수정 이력",
			description = "항목 단위로 무엇이 언제 어떻게 바뀌었는지 남는다.")
	@GetMapping("/{saleFormId}/history")
	public List<SaleFormHistoryResponse> history(@LoginUser SessionUser user,
			@Parameter(description = "판매 폼 id", example = "12") @PathVariable Long saleFormId) {
		return saleFormService.findHistory(user.kakaoId(), saleFormId);
	}
}
