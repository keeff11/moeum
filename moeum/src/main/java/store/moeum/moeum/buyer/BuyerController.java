package store.moeum.moeum.buyer;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.buyer.dto.AddressRequest;
import store.moeum.moeum.buyer.dto.AddressResponse;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

@Tag(name = "구매자", description = "배송지")
@RestController
@RequestMapping("/me/address")
@RequiredArgsConstructor
public class BuyerController {

	private final AddressService addressService;

	/** 등록 전이면 204. 프론트가 빈 폼을 그린다 */
	@GetMapping
	public ResponseEntity<AddressResponse> get(@LoginUser SessionUser user) {
		return addressService.find(user)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PutMapping
	public AddressResponse put(@LoginUser SessionUser user, @Valid @RequestBody AddressRequest request) {
		return addressService.save(user, request);
	}
}
