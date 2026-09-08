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
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.seller.dto.OnboardingRequest;
import store.moeum.moeum.seller.dto.SellerProfileRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class SellerService {

	private final SellerRepository sellerRepository;
	private final ImageStorage imageStorage;

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

	/**
	 * 셀러 페이지 헤더 갱신 (B0).
	 *
	 * 심사용 정보는 건드리지 않는다 — 대표자 실명 · 사업자번호 · 정산계좌는 승인의 근거다.
	 * storeSlug 도 여기서 안 바꾼다. 셀러가 이미 뿌린 링크가 죽는다.
	 */
	@Transactional
	public Seller updateProfile(String kakaoId, SellerProfileRequest request) {
		Seller seller = sellerRepository.findByKakaoId(kakaoId)
				.orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND));

		String imageKey = blankToNull(request.profileImageKey());
		if (!imageStorage.ownsKey(seller.getId(), imageKey)) {
			// 남의 키를 넣으면 남의 이미지를 자기 프로필로 걸어 둘 수 있다
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		changeSlugIfNeeded(seller, request.storeSlug());

		seller.updateProfile(request.storeName(), blankToNull(request.bio()),
				blankToNull(request.socialUrl()), imageKey, blankToNull(request.publicContact()));

		try {
			sellerRepository.flush();
		} catch (DataIntegrityViolationException e) {
			// uk_seller_slug. 미리 확인해도 두 요청이 동시에 통과할 수 있다
			log.warn("판매공간 주소 유니크 위반: storeSlug={}", request.storeSlug());
			throw new BusinessException(ErrorCode.DUPLICATE_STORE_SLUG);
		}
		return seller;
	}

	/**
	 * 판매공간 주소 변경 (와이어프레임 G12 · SALE-105).
	 *
	 * <b>이미 뿌린 링크가 죽는다.</b> 예전 주소로 들어오던 구매자는 404 를 본다.
	 * 화면이 이 기능을 요구하므로 막지 않되, 무슨 일이 일어났는지 알 수 있게 로그를 남긴다 —
	 * "어제까지 되던 링크가 안 된다"는 문의가 오면 이 로그가 유일한 단서다.
	 */
	private void changeSlugIfNeeded(Seller seller, String newSlug) {
		String before = seller.getStoreSlug();
		if (newSlug == null || newSlug.equals(before)) {
			return;
		}
		if (sellerRepository.existsByStoreSlug(newSlug)) {
			throw new BusinessException(ErrorCode.DUPLICATE_STORE_SLUG);
		}
		seller.changeStoreSlug(newSlug);
		log.info("판매공간 주소 변경: sellerId={}, {} → {} (이전 주소 링크는 더 이상 열리지 않는다)",
				seller.getId(), before, newSlug);
	}

	/** 빈 문자열과 null 을 같게 다룬다 — 프론트가 지울 때 어느 쪽을 보낼지 정하게 두지 않는다 */
	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
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
