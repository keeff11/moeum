package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.dto.ProductAvailabilityResponse;
import store.moeum.moeum.saleform.dto.ProductDetailResponse;

import java.time.LocalDateTime;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 구매자용 공개 상품 조회. 로그인이 필요 없다.
 *
 * 셀러용 {@link SaleFormService} 와 나눈 이유는 소유권 검사가 정반대이기 때문이다.
 * 저쪽은 "내 폼인가"를 묻고, 여기는 누구에게 보여도 되는 폼인가를 묻는다.
 * 한 서비스에 섞으면 검사 하나를 빠뜨렸을 때 남의 미발행 폼이 그대로 나간다.
 */
@Service
@RequiredArgsConstructor
public class PublicProductService {

	private final SaleFormRepository saleFormRepository;

	@Transactional(readOnly = true)
	public ProductDetailResponse detail(Long saleFormId) {
		SaleForm form = saleFormRepository.findPublicDetailById(saleFormId)
				.filter(PublicProductService::isPublished)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		return ProductDetailResponse.of(form, LocalDateTime.now(KST));
	}

	@Transactional(readOnly = true)
	public ProductAvailabilityResponse availability(Long saleFormId) {
		SaleForm form = saleFormRepository.findById(saleFormId)
				.filter(PublicProductService::isPublished)
				.orElseThrow(() -> new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND));

		return ProductAvailabilityResponse.of(form, LocalDateTime.now(KST));
	}

	/**
	 * DRAFT 는 없는 것으로 취급한다.
	 *
	 * 403 이 아니라 404 다. 403 이면 "그 id 에 폼이 있긴 하다"는 사실이 새어 나가고,
	 * id 를 훑어 셀러가 준비 중인 상품의 존재를 알아낼 수 있다.
	 */
	private static boolean isPublished(SaleForm form) {
		return form.getStatus() != SaleFormStatus.DRAFT;
	}
}
