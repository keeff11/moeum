package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerRepository;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BuyerService {

	private final BuyerRepository buyerRepository;

	/**
	 * 로그인한 카카오 계정으로 구매자를 찾거나 만든다.
	 *
	 * 셀러와 달리 별도 가입 절차가 없다. 카카오 로그인이 곧 가입이다 (D-015).
	 * 동시에 두 요청이 들어와도 uk_buyer_kakao 가 막고, 그때는 이미 만들어진 행을 읽는다.
	 */
	@Transactional
	public Buyer findOrCreate(SessionUser user) {
		return buyerRepository.findByKakaoId(user.kakaoId())
				.map(buyer -> {
					buyer.updateNickname(user.nickname());
					return buyer;
				})
				.orElseGet(() -> createOrReread(user.kakaoId(), user.nickname()));
	}

	@Transactional(readOnly = true)
	public Optional<Buyer> findByKakaoId(String kakaoId) {
		return buyerRepository.findByKakaoId(kakaoId);
	}

	@Transactional(readOnly = true)
	public Buyer getByKakaoId(String kakaoId) {
		return buyerRepository.findByKakaoId(kakaoId)
				.orElseThrow(() -> new BusinessException(ErrorCode.BUYER_NOT_FOUND));
	}

	private Buyer createOrReread(String kakaoId, String nickname) {
		try {
			return buyerRepository.saveAndFlush(Buyer.of(kakaoId, nickname));
		} catch (DataIntegrityViolationException e) {
			// 같은 계정으로 동시에 두 요청이 들어온 경우
			return buyerRepository.findByKakaoId(kakaoId)
					.orElseThrow(() -> new BusinessException(ErrorCode.BUYER_NOT_FOUND));
		}
	}
}
