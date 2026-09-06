package store.moeum.moeum.seller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.seller.dto.OnboardingRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class SellerService {

	private final SellerRepository sellerRepository;

	/**
	 * 온보딩 제출. 카카오 계정 하나당 셀러 하나다.
	 *
	 * 중복 검사를 미리 하지만 그것만 믿지 않는다. 두 요청이 동시에 통과할 수 있어서
	 * 최종 판정은 DB 의 유니크 키(uk_seller_kakao · uk_seller_slug)에 맡기고
	 * 위반을 잡아 409 로 바꾼다.
	 */
	@Transactional
	public Seller submitOnboarding(String kakaoId, OnboardingRequest request) {
		if (sellerRepository.existsByKakaoId(kakaoId)) {
			throw new BusinessException(ErrorCode.SELLER_ALREADY_REGISTERED);
		}
		if (sellerRepository.existsByStoreSlug(request.storeSlug())) {
			throw new BusinessException(ErrorCode.DUPLICATE_STORE_SLUG);
		}

		Seller seller = Seller.builder()
				.kakaoId(kakaoId)
				.storeSlug(request.storeSlug())
				.storeName(request.storeName())
				.shippingFee(request.shippingFee())
				.freeShippingOver(request.freeShippingOver())
				.businessNo(request.businessNo())
				.settlementAccount(request.settlementAccount())
				.representativeName(request.representativeName())
				.phone(request.phone())
				.email(request.email())
				.build();

		try {
			return sellerRepository.saveAndFlush(seller);
		} catch (DataIntegrityViolationException e) {
			log.warn("셀러 온보딩 유니크 위반: storeSlug={}", request.storeSlug());
			throw new BusinessException(ErrorCode.DUPLICATE_STORE_SLUG);
		}
	}

	@Transactional(readOnly = true)
	public Seller getByKakaoId(String kakaoId) {
		return sellerRepository.findByKakaoId(kakaoId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND));
	}

	/** 심사 승인. 로드맵상 아직 수동 API 다 */
	@Transactional
	public Seller approve(Long sellerId) {
		Seller seller = findById(sellerId);
		seller.approve();
		return seller;
	}

	@Transactional
	public Seller reject(Long sellerId) {
		Seller seller = findById(sellerId);
		seller.reject();
		return seller;
	}

	private Seller findById(Long sellerId) {
		return sellerRepository.findById(sellerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND));
	}
}
