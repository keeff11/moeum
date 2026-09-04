package store.moeum.moeum.buyer;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerAddress;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.buyer.dto.AddressRequest;
import store.moeum.moeum.buyer.dto.AddressResponse;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.ErrorCode;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AddressService {

	private final BuyerAddressRepository addressRepository;
	private final BuyerService buyerService;

	/**
	 * 아직 구매자 행이 없으면(카카오 로그인만 하고 아무것도 안 한 상태) 빈 결과다.
	 * 조회 때문에 계정을 만들지는 않는다.
	 */
	@Transactional(readOnly = true)
	public Optional<AddressResponse> find(SessionUser user) {
		return buyerService.findByKakaoId(user.kakaoId())
				.flatMap(buyer -> addressRepository.findByBuyerId(buyer.getId()))
				.map(AddressResponse::from);
	}

	/**
	 * 등록·수정. 구매자당 한 행이라 PUT 한 번으로 upsert 한다 (멱등).
	 *
	 * 결제 요청과 분리해 둔 이유: 결제가 실패해도 주소는 남아 있어야 한다.
	 * 한 트랜잭션에 묶으면 카드만 바꿔 재시도하려는 사용자가 주소를 다시 입력하게 된다.
	 */
	@Transactional
	public AddressResponse save(SessionUser user, AddressRequest request) {
		// 구매자 행위의 진입점이다. 여기서 처음 계정이 만들어진다 (D-015 — 카카오 로그인이 곧 가입)
		Buyer buyer = buyerService.findOrCreate(user);

		BuyerAddress address = addressRepository.findByBuyerId(buyer.getId())
				.map(existing -> {
					existing.replaceWith(request.recipientName(), request.phone(), request.postalCode(),
							request.address1(), request.address2(), request.memo());
					return existing;
				})
				.orElseGet(() -> addressRepository.save(BuyerAddress.builder()
						.buyer(buyer)
						.recipientName(request.recipientName())
						.phone(request.phone())
						.postalCode(request.postalCode())
						.address1(request.address1())
						.address2(request.address2())
						.memo(request.memo())
						.build()));

		addressRepository.flush();
		return AddressResponse.from(address);
	}

	/** 결제 시 shipping 스냅샷을 뜨려면 배송지가 반드시 있어야 한다 (4단계에서 사용) */
	@Transactional(readOnly = true)
	public BuyerAddress getRequired(Long buyerId) {
		return addressRepository.findByBuyerId(buyerId)
				.orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_REQUIRED));
	}
}
