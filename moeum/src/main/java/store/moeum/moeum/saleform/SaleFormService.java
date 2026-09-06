package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.saleform.dto.ImageUploadUrlResponse;
import store.moeum.moeum.saleform.dto.ImageUploadUrlRequest;
import store.moeum.moeum.saleform.domain.FieldChange;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormHistory;
import store.moeum.moeum.saleform.domain.SaleFormHistoryRepository;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormUpdate;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.SaleFormCreateRequest;
import store.moeum.moeum.saleform.dto.SaleFormDetailResponse;
import store.moeum.moeum.saleform.dto.SaleFormHistoryResponse;
import store.moeum.moeum.saleform.dto.SaleFormSummaryResponse;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaleFormService {

	private final SaleFormRepository saleFormRepository;
	private final ImageStorage imageStorage;
	private final SaleFormHistoryRepository saleFormHistoryRepository;
	private final SellerService sellerService;

	@Transactional
	public Long create(String kakaoId, SaleFormCreateRequest request) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		if (!seller.isApproved()) {
			throw new BusinessException(ErrorCode.SELLER_NOT_APPROVED);
		}

		validate(request);

		if (saleFormRepository.existsBySellerIdAndSlug(seller.getId(), request.slug())) {
			throw new BusinessException(ErrorCode.DUPLICATE_SALE_FORM_SLUG);
		}

		boolean group = request.saleType() == SaleType.GROUP;

		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title(request.title())
				.slug(request.slug())
				.saleType(request.saleType())
				.stockMax(request.stockMax())
				// SOLO 는 목표수량이라는 개념이 없다. 요청에 실려 와도 버린다
				.targetQty(group ? request.targetQty() : null)
				.maxPerUser(request.maxPerUser())
				.opensAt(request.opensAt())
				.closesAt(request.closesAt())
				// 미달 정책도 목표수량이 있어야 의미가 있으므로 SOLO 는 비운다
				.shortfallPolicy(group ? request.shortfallPolicy() : null)
				.shipStartText(request.shipStartText())
				.minOrderAmount(request.minOrderAmount())
				.descriptionJson(request.descriptionJson())
				.progressPublic(request.progressPublic())
				.build();

		form.replaceImages(request.images());

		for (SaleFormCreateRequest.ProductRequest productRequest : request.products()) {
			Product product = Product.builder()
					.name(productRequest.name())
					.sortOrder(productRequest.sortOrder())
					.build();

			for (SaleFormCreateRequest.OptionRequest optionRequest : productRequest.options()) {
				product.addOption(ProductOption.builder()
						.name(optionRequest.name())
						.deposit1Amount(optionRequest.deposit1Amount())
						.deposit2Amount(optionRequest.deposit2Amount())
						.sortOrder(optionRequest.sortOrder())
						.build());
			}
			form.addProduct(product);
		}

		try {
			return saleFormRepository.saveAndFlush(form).getId();
		} catch (DataIntegrityViolationException e) {
			// uk_sale_form_slug (seller_id, slug) — 동시 요청은 여기서 걸린다
			log.warn("판매 폼 슬러그 유니크 위반: sellerId={}, slug={}", seller.getId(), request.slug());
			throw new BusinessException(ErrorCode.DUPLICATE_SALE_FORM_SLUG);
		}
	}

	@Transactional(readOnly = true)
	public List<SaleFormSummaryResponse> findMine(String kakaoId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		return saleFormRepository.findBySellerIdOrderByIdDesc(seller.getId()).stream()
				.map(SaleFormSummaryResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public SaleFormDetailResponse findMineDetail(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm owned = findOwned(seller, saleFormId);
		return SaleFormDetailResponse.of(owned, seller, imageUrlsOf(owned));
	}

	/**
	 * 판매 폼 수정. 바뀐 필드마다 sale_form_history 를 남긴다.
	 *
	 * slug · saleType · 상품 · 옵션은 수정 대상이 아니다 ({@link SaleFormUpdate} 주석 참고).
	 */
	@Transactional
	public SaleFormDetailResponse update(String kakaoId, Long saleFormId, SaleFormUpdate command) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		validateGroupRules(form.getSaleType(), command.targetQty(), command.closesAt(), command.stockMax());
		validateSchedule(command.opensAt(), command.closesAt());

		// 이미 팔렸거나 선점된 수량 밑으로 재고를 줄이면 초과 판매가 된다
		if (command.stockMax() < form.committedQty()) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"이미 판매·선점된 수량(" + form.committedQty() + "개)보다 적게 줄일 수 없습니다.");
		}

		List<FieldChange> changes = form.update(command);

		if (!changes.isEmpty()) {
			saleFormHistoryRepository.saveAll(changes.stream()
					.map(change -> SaleFormHistory.of(form.getId(), change.field(),
							change.oldValue(), change.newValue(), seller.getId()))
					.toList());
		}

		return SaleFormDetailResponse.of(form, seller, imageUrlsOf(form));
	}

	@Transactional(readOnly = true)
	public List<SaleFormHistoryResponse> findHistory(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		findOwned(seller, saleFormId);

		return saleFormHistoryRepository.findBySaleFormIdOrderByIdDesc(saleFormId).stream()
				.map(SaleFormHistoryResponse::from)
				.toList();
	}

	private SaleForm findOwned(Seller seller, Long saleFormId) {
		SaleForm form = saleFormRepository.findDetailById(saleFormId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		// 남의 폼 id 를 넣어 봤을 때 "있는데 권한 없음"과 "없음"을 구분해 주지 않는다
		if (!form.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
		return form;
	}

	private void validate(SaleFormCreateRequest request) {
		validateGroupRules(request.saleType(), request.targetQty(), request.closesAt(), request.stockMax());
		validateSchedule(request.opensAt(), request.closesAt());

		if (request.saleType() == SaleType.SOLO) {
			boolean hasSecondDeposit = request.products().stream()
					.flatMap(product -> product.options().stream())
					.anyMatch(option -> option.deposit2Amount() != 0);

			if (hasSecondDeposit) {
				throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
						"단독 판매는 2차금을 둘 수 없습니다. deposit2Amount 는 0이어야 합니다.");
			}
		}
	}

	/**
	 * GROUP 은 목표수량을 채우는 게 목적이라 목표수량과 마감이 없으면 성립하지 않는다.
	 * SOLO 는 상시 판매라 목표수량이 없다 — 값이 실려 와도 서비스가 버린다.
	 */
	private void validateGroupRules(SaleType saleType, Integer targetQty, LocalDateTime closesAt, int stockMax) {
		if (saleType != SaleType.GROUP) {
			return;
		}
		if (targetQty == null) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "공동구매는 목표수량이 필요합니다.");
		}
		if (closesAt == null) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "공동구매는 마감일시가 필요합니다.");
		}
		if (targetQty > stockMax) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "목표수량이 재고보다 클 수 없습니다.");
		}
	}

	private void validateSchedule(LocalDateTime opensAt, LocalDateTime closesAt) {
		if (opensAt != null && closesAt != null && !opensAt.isBefore(closesAt)) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "마감일시는 오픈일시보다 뒤여야 합니다.");
		}
	}

	/** 엔티티에는 S3 키만 있다. 읽기용 주소는 여기서 조립한다 */
	private List<String> imageUrlsOf(SaleForm form) {
		return form.imageKeys().stream().map(imageStorage::publicUrl).toList();
	}

	/**
	 * 이미지 업로드용 presigned URL 을 발급한다.
	 *
	 * 심사를 통과한 셀러만 받는다 — 미승인 셀러가 버킷에 파일을 쌓을 이유가 없다.
	 * 키는 셀러 id 로 나뉘므로 남의 경로에 쓸 수 없다.
	 *
	 * <b>여기서 발급만 하고 파일은 브라우저가 S3 로 직접 올린다.</b>
	 * 올린 뒤 판매 폼을 저장하지 않으면 그 파일은 아무 데서도 참조되지 않는다 —
	 * 버킷 수명주기 규칙으로 걷어내야 한다 (D-022).
	 */
	@Transactional(readOnly = true)
	public ImageUploadUrlResponse issueImageUploadUrl(String kakaoId, ImageUploadUrlRequest request) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		if (!seller.isApproved()) {
			throw new BusinessException(ErrorCode.SELLER_NOT_APPROVED);
		}

		return ImageUploadUrlResponse.from(imageStorage.presignUpload(
				seller.getId(), request.contentType(), request.contentLength()));
	}
}
