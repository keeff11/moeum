package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.WishlistRepository;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;

import java.time.LocalDateTime;
import java.util.List;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 찜(하트) — 셀러 페이지 카드와 상품 상세에서 누른다 (D-029).
 *
 * <b>찜과 해제 둘 다 멱등하다.</b> 하트는 연타되는 버튼이라 두 번 눌렀다고
 * 409 를 돌려주면 화면이 깜빡인다. 같은 상태로 만들어 달라는 요청으로 본다.
 */
@Service
@RequiredArgsConstructor
public class WishlistService {

	private final WishlistRepository wishlistRepository;
	private final SaleFormRepository saleFormRepository;
	private final BuyerService buyerService;

	/**
	 * 찜한다.
	 *
	 * 구매자 행은 여기서 만들어질 수 있다 — 주문 한 번 없이 하트부터 누르는 것이 정상이다.
	 */
	@Transactional
	public void add(SessionUser user, Long saleFormId) {
		requirePublished(saleFormId);
		Buyer buyer = buyerService.findOrCreate(user);

		// 유니크 위반을 예외로 받으면 트랜잭션이 rollback-only 가 된다. DB 가 중복을 삼키게 한다
		wishlistRepository.insertIfAbsent(buyer.getId(), saleFormId, LocalDateTime.now(KST));
	}

	/** 찜 해제. 없는 것을 지워도 성공으로 본다 */
	@Transactional
	public void remove(SessionUser user, Long saleFormId) {
		buyerService.findByKakaoId(user.kakaoId())
				.ifPresent(buyer -> wishlistRepository.delete(buyer.getId(), saleFormId));
	}

	/**
	 * 하트를 칠할 폼 id 목록.
	 *
	 * 셀러 페이지 목록과 따로 받는다. 목록 응답에 이 값을 실으면 응답이 사용자별로 갈려
	 * 모두에게 같은 것을 줄 수 없게 된다 (D-029).
	 *
	 * 주문한 적 없는 사용자는 buyer 행이 없다 — 빈 목록이지 오류가 아니다.
	 */
	@Transactional(readOnly = true)
	public List<Long> saleFormIds(SessionUser user) {
		return buyerService.findByKakaoId(user.kakaoId())
				.map(buyer -> wishlistRepository.findSaleFormIds(buyer.getId()))
				.orElseGet(List::of);
	}

	/**
	 * 미발행 폼은 찜할 수 없다.
	 *
	 * 404 인 이유는 공개 조회와 같다 — 403 이면 "그 id 에 폼이 있긴 하다"가 새어 나가
	 * id 를 훑어 셀러가 준비 중인 상품의 존재를 알아낼 수 있다.
	 */
	private void requirePublished(Long saleFormId) {
		boolean published = saleFormRepository.findById(saleFormId)
				.map(form -> form.getStatus() != SaleFormStatus.DRAFT)
				.orElse(false);

		if (!published) {
			throw new BusinessException(ErrorCode.SALE_FORM_NOT_FOUND);
		}
	}
}
