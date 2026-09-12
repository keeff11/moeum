package store.moeum.moeum.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.LoginUser;
import store.moeum.moeum.global.auth.SessionUser;

import java.util.List;

/**
 * 택배사 목록 (D-048).
 *
 * 송장 등록 화면의 선택지다. <b>목록을 우리가 들고 있지 않고 스마트택배에서 받아 온다</b> —
 * 택배사는 늘고 코드는 그쪽이 정한다. 화면이 이 목록에서 고르게 하면 등록되는 코드가
 * 항상 조회에 쓸 수 있는 코드다.
 *
 * {@code /seller/orders} 아래가 아니라 따로 둔 것은 주문이 아니기 때문이다.
 */
@Tag(name = "택배사", description = "송장 등록 화면의 택배사 선택지")
@RestController
@RequestMapping("/seller/carriers")
@RequiredArgsConstructor
public class CarrierController {

	private final TrackingService trackingService;

	@Operation(summary = "택배사 목록",
			description = """
					송장 등록 화면에서 고를 택배사 목록을 준다. code 를 그대로 송장 등록의
					carrierCode 로 보내면 된다.

					★ 조회 키가 없거나 그쪽이 죽으면 빈 목록이 나간다. 오류가 아니다 —
					  배송조회는 부가 기능이라 목록을 못 받았다고 송장 등록을 막지 않는다.
					  그때는 택배사를 직접 입력받고 carrierCode 없이 등록하면 된다.
					""")
	@GetMapping
	public List<CarrierResponse> list(@LoginUser SessionUser user) {
		return trackingService.carriers().stream()
				.map(carrier -> new CarrierResponse(carrier.code(), carrier.name()))
				.toList();
	}

	@Schema(description = "택배사 하나")
	public record CarrierResponse(

			@Schema(description = "송장 등록의 carrierCode 로 그대로 보낼 값", example = "04")
			String code,

			@Schema(description = "화면에 보여 줄 이름", example = "CJ대한통운")
			String name) {
	}
}
