package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderRepository;
import store.moeum.moeum.outbox.OutboxRecorder;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.saleform.dto.ImageUploadUrlResponse;
import store.moeum.moeum.saleform.dto.ImageUploadUrlRequest;
import store.moeum.moeum.saleform.domain.FieldChange;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
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

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaleFormService {

	private final SaleFormRepository saleFormRepository;
	private final OrderRepository orderRepository;
	private final OutboxRecorder outboxRecorder;
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

	/**
	 * 판매 시작 (DRAFT → SELLING). 일시중지된 폼을 다시 여는 데도 쓴다.
	 *
	 * 생성 시점에도 GROUP 규칙을 검사하지만 여기서 한 번 더 본다 —
	 * 수정으로 마감일이 과거가 됐을 수 있고, 그 상태로 열면 마감 배치가 1분 안에 도로 닫는다.
	 * 셀러 눈에는 "열었는데 안 열린다" 로 보인다.
	 */
	@Transactional
	public SaleFormDetailResponse startSelling(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		if (!form.isStartable()) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"마감된 판매 폼은 다시 열 수 없습니다. 새로 만들어 주세요.");
		}
		if (form.getProducts().isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "상품이 없는 폼은 열 수 없습니다.");
		}

		LocalDateTime now = LocalDateTime.now(KST);
		if (form.getClosesAt() != null && !form.getClosesAt().isAfter(now)) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"마감일시가 이미 지났습니다. 마감일시를 먼저 수정해 주세요.");
		}
		if (form.getSaleType() == SaleType.GROUP && form.getTargetQty() == null) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM, "공동구매는 목표수량이 필요합니다.");
		}

		recordStatusChange(form, form.startSelling(), seller);
		return SaleFormDetailResponse.of(form, seller, imageUrlsOf(form));
	}

	/**
	 * 일시중지 (SELLING → PAUSED). 구매 버튼만 막는다.
	 *
	 * <b>이미 잡힌 홀드는 풀지 않는다.</b> 결제 중인 구매자를 중간에 끊으면
	 * 승인은 나가고 재고는 없는 상태가 된다. 그 홀드들은 15분 뒤 만료 배치가 정리한다.
	 */
	@Transactional
	public SaleFormDetailResponse pause(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		if (form.getStatus() != SaleFormStatus.SELLING && form.getStatus() != SaleFormStatus.PAUSED) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"판매 중인 폼만 일시중지할 수 있습니다.");
		}

		recordStatusChange(form, form.pause(), seller);
		return SaleFormDetailResponse.of(form, seller, imageUrlsOf(form));
	}

	/**
	 * 수동 마감 (SELLING · PAUSED → CLOSED). 마감 시각을 기다리지 않고 셀러가 닫는다.
	 *
	 * <b>되돌릴 수 없다.</b> 목표수량 미달 처리(shortfall_policy)는 여기서 하지 않는다 — 6단계다.
	 */
	@Transactional
	public SaleFormDetailResponse close(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		if (form.getStatus() == SaleFormStatus.DRAFT || form.getStatus() == SaleFormStatus.ENDED) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"마감할 수 없는 상태입니다: " + form.getStatus());
		}

		recordStatusChange(form, form.close(), seller);

		// 폼만 마감하면 주문은 모집 중에 머물러 구매자 화면이 "모집 중" 으로 남는다 (D-049)
		int closed = closeOrders(saleFormId);
		if (closed > 0) {
			log.info("모집 마감: saleFormId={}, 주문 {}건", saleFormId, closed);
		}
		return SaleFormDetailResponse.of(form, seller, imageUrlsOf(form));
	}

	/**
	 * 입고 처리 (5단계 시작점). 이 폼의 1차금 확정 주문을 ARRIVED 로 넘긴다.
	 *
	 * <b>여기서 2차금이 청구 가능해진다.</b> 다만 실제로 열리는 것은 묶음의 <em>모든</em> 폼이
	 * 입고된 뒤다 — 배송비가 묶음당 1회라 일부만 입고됐다고 청구하면 배송비를 나눌 수 없다.
	 *
	 * @return 이번에 입고 처리된 주문 수
	 */
	@Transactional
	public int markArrived(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		if (form.getStatus() == SaleFormStatus.DRAFT) {
			throw new BusinessException(ErrorCode.INVALID_SALE_FORM,
					"판매를 시작하지 않은 폼은 입고 처리할 수 없습니다.");
		}

		int arrived = 0;
		for (Order order : orderRepository.findArrivableBySaleForm(saleFormId)) {
			if (!order.markArrived()) {
				continue;
			}
			arrived++;
			// 묶음의 마지막 폼이 들어오는 순간에만 청구가 열린다 (payment-flow 2절).
			// 여기서 알리지 않으면 구매자는 잔금을 낼 때가 됐다는 걸 알 방법이 없다
			notifySecondDue(order.getOrderGroup());
		}
		log.info("입고 처리: saleFormId={}, 주문 {}건", saleFormId, arrived);
		return arrived;
	}

	/**
	 * 발주 · 제작 시작 (D-049). 셀러가 누른다.
	 *
	 * <b>모집이 마감된 주문만 넘어간다.</b> 모집이 끝나야 몇 개를 만들지가 정해지고,
	 * 그 전에 발주하면 발주서(D-045)의 수량과 어긋난다.
	 *
	 * 알림은 적재하지 않는다 — 진행 상태 변경 알림(항목 4)은 템플릿이 아직 없다.
	 * 자리는 여기다.
	 */
	@Transactional
	public int startProducing(String kakaoId, Long saleFormId) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		SaleForm form = findOwned(seller, saleFormId);

		int producing = 0;
		for (Order order : orderRepository.findClosedBySaleForm(saleFormId)) {
			if (order.markProducing()) {
				producing++;
			}
		}
		log.info("발주 처리: saleFormId={}, 주문 {}건", form.getId(), producing);
		return producing;
	}

	/** 모집 중인 주문을 마감으로 넘긴다. 폼 마감과 같이 부른다 */
	private int closeOrders(Long saleFormId) {
		int closed = 0;
		for (Order order : orderRepository.findRecruitingBySaleForm(saleFormId)) {
			if (order.markClosed()) {
				closed++;
			}
		}
		return closed;
	}

	/**
	 * 2차금 청구 알림을 적재한다 (D-012).
	 *
	 * <b>이 알림이 곧 결제 요청이다.</b> 유실되면 구매자는 잔금을 낼 줄 모르고
	 * 셀러는 미수로 남은 이유를 알 수 없다.
	 *
	 * 입고 전이가 실제로 일어난 경우에만 불린다 — 같은 폼을 다시 입고 처리해도
	 * {@code markArrived} 가 false 를 주므로 알림이 쌓이지 않는다.
	 */
	private void notifySecondDue(OrderGroup group) {
		if (!group.isSecondPaymentDue()) {
			return;
		}
		outboxRecorder.record(OutboxAggregate.ORDER_GROUP, group.getId(),
				OutboxEventType.SECOND_PAYMENT_DUE,
				java.util.Map.of(
						"orderToken", group.getOrderToken(),
						"buyerId", group.getBuyer().getId(),
						"amount", group.secondPaymentAmount()));
	}

	/** 상태 전이도 다른 필드와 같이 sale_form_history 에 남긴다 */
	private void recordStatusChange(SaleForm form, FieldChange change, Seller seller) {
		if (change == null) {
			return;
		}
		saleFormHistoryRepository.save(SaleFormHistory.of(
				form.getId(), change.field(), change.oldValue(), change.newValue(), seller.getId()));
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
