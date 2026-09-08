package store.moeum.moeum.saleform;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.global.storage.ImageStorage;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.saleform.dto.StorePageResponse;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;

import java.time.LocalDateTime;
import java.util.List;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 셀러 페이지 (B0). 로그인이 필요 없다.
 *
 * 셀러가 링크(`/{storeSlug}`)를 뿌리고 구매자가 그 링크로 들어오는 구조라,
 * 이 화면이 구매자 유입의 시작점이다.
 *
 * 셀러용 {@link SaleFormService} 와 나눈 이유는 {@link PublicProductService} 와 같다 —
 * 저쪽은 "내 폼인가"를 묻고 여기는 "누구에게 보여도 되는 폼인가"를 묻는다.
 */
@Service
@RequiredArgsConstructor
public class PublicStoreService {

	/** 한 번에 내려주는 카드 수. 무한 스크롤이라 넉넉할 필요가 없다 */
	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;

	private final SellerRepository sellerRepository;
	private final SaleFormRepository saleFormRepository;
	private final ImageStorage imageStorage;

	@Transactional(readOnly = true)
	public StorePageResponse page(String storeSlug, SaleType saleType, String q, int page, int size) {
		Seller seller = sellerRepository.findByStoreSlug(storeSlug)
				.filter(Seller::isApproved)
				.orElseThrow(() -> new BusinessException(ErrorCode.SELLER_NOT_FOUND));

		Page<SaleForm> forms = saleFormRepository.findStorePage(
				seller.getId(),
				saleType == null ? null : saleType.name(),
				likePattern(q),
				PageRequest.of(Math.max(page, 0), clampSize(size)));

		LocalDateTime now = LocalDateTime.now(KST);
		List<StorePageResponse.StoreItem> items = forms.getContent().stream()
				.map(form -> StorePageResponse.itemOf(form, now, thumbnailOf(form)))
				.toList();

		return new StorePageResponse(
				StorePageResponse.sellerOf(seller, profileImageOf(seller)),
				items,
				new StorePageResponse.PageInfo(forms.getNumber(), forms.getSize(),
						forms.getTotalElements(), forms.getTotalPages(), forms.hasNext()));
	}

	// ---------------------------------------------------------------- 내부

	/** 첫 번째 이미지가 카드 썸네일이다. 이미지가 없는 폼은 null 로 두고 프론트가 자리표시자를 넣는다 */
	private String thumbnailOf(SaleForm form) {
		return form.imageKeys().stream().findFirst().map(imageStorage::publicUrl).orElse(null);
	}

	private String profileImageOf(Seller seller) {
		return seller.getProfileImageKey() == null
				? null
				: imageStorage.publicUrl(seller.getProfileImageKey());
	}

	/**
	 * 검색어를 LIKE 패턴으로 만든다.
	 *
	 * <b>{@code %} 와 {@code _} 를 escape 한다.</b> 그냥 끼워 넣으면 구매자가 친 {@code %} 가
	 * 와일드카드로 동작해서 상품 전체가 걸린다. 쿼리는 {@code ESCAPE '!'} 로 받는다.
	 */
	private static String likePattern(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String escaped = q.trim()
				.replace("!", "!!")
				.replace("%", "!%")
				.replace("_", "!_");
		return "%" + escaped + "%";
	}

	private static int clampSize(int size) {
		if (size <= 0) {
			return DEFAULT_SIZE;
		}
		return Math.min(size, MAX_SIZE);
	}
}
