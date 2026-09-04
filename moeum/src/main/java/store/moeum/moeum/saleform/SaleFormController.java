package store.moeum.moeum.saleform;

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

	@PostMapping
	public ResponseEntity<SaleFormDetailResponse> create(@LoginUser SessionUser user,
	                                                     @Valid @RequestBody SaleFormCreateRequest request) {
		Long id = saleFormService.create(user.kakaoId(), request);
		return ResponseEntity.created(URI.create("/seller/sale-forms/" + id))
				.body(saleFormService.findMineDetail(user.kakaoId(), id));
	}

	@GetMapping
	public List<SaleFormSummaryResponse> list(@LoginUser SessionUser user) {
		return saleFormService.findMine(user.kakaoId());
	}

	@GetMapping("/{saleFormId}")
	public SaleFormDetailResponse detail(@LoginUser SessionUser user, @PathVariable Long saleFormId) {
		return saleFormService.findMineDetail(user.kakaoId(), saleFormId);
	}

	/** 전체 교체(PUT). 보내지 않은 선택 필드는 비워진다 */
	@PutMapping("/{saleFormId}")
	public SaleFormDetailResponse update(@LoginUser SessionUser user,
	                                     @PathVariable Long saleFormId,
	                                     @Valid @RequestBody SaleFormUpdateRequest request) {
		return saleFormService.update(user.kakaoId(), saleFormId, request.toCommand());
	}

	@GetMapping("/{saleFormId}/history")
	public List<SaleFormHistoryResponse> history(@LoginUser SessionUser user, @PathVariable Long saleFormId) {
		return saleFormService.findHistory(user.kakaoId(), saleFormId);
	}
}
