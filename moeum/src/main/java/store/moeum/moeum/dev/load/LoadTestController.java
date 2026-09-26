package store.moeum.moeum.dev.load;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import store.moeum.moeum.global.auth.SessionKeys;
import store.moeum.moeum.global.auth.SessionUser;

/**
 * HTTP 부하 테스트용 준비 통로. <b>로컬 프로파일에서만 등록된다.</b>
 *
 * 카카오를 거치지 않고 세션을 만든다 — 구매자 1000 명을 카카오로 로그인시킬 수는 없어서다.
 * 그래서 운영에 있으면 안 되는 통로다. prod 프로파일에서는 빈이 아예 만들어지지 않는다.
 * kakaoId 도 {@code load-buyer-} 로 시작하는 값만 받아, 로컬의 실제 계정으로 들어가지 못하게 한다.
 */
@Tag(name = "개발 도구(local 전용)", description = "HTTP 부하 테스트 준비. 운영에는 등록되지 않는다")
@RestController
@RequestMapping("/dev/load")
@Profile("local")
@RequiredArgsConstructor
@Validated
public class LoadTestController {

	private final LoadTestService loadTestService;

	@Operation(summary = "부하 테스트용 로그인", description = "세션 쿠키(MOEUM_SESSION)를 발급한다")
	@PostMapping("/login")
	public ResponseEntity<Void> login(
			@RequestParam @Pattern(regexp = LoadTestService.BUYER_PREFIX + "[0-9]{1,6}") String kakaoId,
			HttpServletRequest request) {
		request.getSession(true).setAttribute(SessionKeys.LOGIN_USER, new SessionUser(kakaoId, kakaoId));
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "부하 테스트용 판매 폼 만들기")
	@PostMapping("/sale-forms")
	public LoadTestService.Fixture createSaleForm(
			@RequestParam(defaultValue = "100") @Min(1) @Max(100_000) int stock) {
		return loadTestService.createSaleForm(stock);
	}

	@Operation(summary = "판매 폼 재고 장부 조회", description = "held · sold · HELD 홀드 행 수를 준다")
	@GetMapping("/sale-forms/{saleFormId}")
	public LoadTestService.Snapshot snapshot(@PathVariable Long saleFormId) {
		return loadTestService.snapshot(saleFormId);
	}

	@Operation(summary = "부하 테스트 데이터 지우기", description = "load- 접두어가 붙은 행만 지운다")
	@DeleteMapping
	public ResponseEntity<Void> cleanUp() {
		loadTestService.cleanUp();
		return ResponseEntity.noContent().build();
	}
}
