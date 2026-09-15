package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerRefundAccount;
import store.moeum.moeum.buyer.domain.BuyerRefundAccountRepository;
import store.moeum.moeum.buyer.dto.RefundAccountRequest;
import store.moeum.moeum.buyer.dto.RefundAccountResponse;
import store.moeum.moeum.global.auth.SessionUser;

import java.util.Optional;

/**
 * 환불 계좌 (D-057). {@link AddressService} 와 같은 모양이다 — 구매자당 한 행이고 PUT 으로 교체한다.
 */
@Service
@RequiredArgsConstructor
public class RefundAccountService {

	private final BuyerRefundAccountRepository refundAccountRepository;
	private final BuyerService buyerService;

	/**
	 * 아직 구매자 행이 없으면(카카오 로그인만 한 상태) 빈 결과다.
	 * 조회 때문에 계정을 만들지는 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<RefundAccountResponse> find(SessionUser user) {
		return buyerService.findByKakaoId(user.kakaoId())
				.flatMap(buyer -> refundAccountRepository.findByBuyerId(buyer.getId()))
				.map(RefundAccountResponse::from);
	}

	/**
	 * 등록·수정. 구매자당 한 행이라 PUT 한 번으로 upsert 한다 (멱등).
	 *
	 * 배송지와 마찬가지로 결제 요청과 분리해 둔다 — 결제가 실패해도 계좌는 남아 있어야 한다.
	 * 한 트랜잭션에 묶으면 카드만 바꿔 재시도하려는 사용자가 계좌를 다시 입력하게 된다.
	 */
	@Transactional
	public RefundAccountResponse save(SessionUser user, RefundAccountRequest request) {
		Buyer buyer = buyerService.findOrCreate(user);
		String accountNo = normalize(request.accountNo());

		BuyerRefundAccount account = refundAccountRepository.findByBuyerId(buyer.getId())
				.map(existing -> {
					existing.replaceWith(request.bank(), accountNo, request.holderName());
					return existing;
				})
				.orElseGet(() -> refundAccountRepository.save(BuyerRefundAccount.builder()
						.buyer(buyer)
						.bank(request.bank())
						.accountNo(accountNo)
						.holderName(request.holderName())
						.build()));

		refundAccountRepository.flush();
		return RefundAccountResponse.from(account);
	}

	/**
	 * 하이픈을 떼고 숫자만 남긴다.
	 *
	 * 같은 계좌를 어떤 사람은 하이픈을 넣고 어떤 사람은 빼고 적는데, 암호화 컬럼이라
	 * 나중에 정규화할 방법이 없다 — 전부 복호화해 다시 써야 한다. 들어올 때 맞춰 둔다.
	 */
	private static String normalize(String accountNo) {
		return accountNo.replaceAll("\\D", "");
	}
}
