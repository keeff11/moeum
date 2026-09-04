package store.moeum.moeum.dev;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동시성 실험용. <b>로컬 프로파일에서만 등록된다.</b>
 * prod 프로파일에서는 이 빈이 아예 만들어지지 않는다.
 */
@Tag(name = "개발 도구(local 전용)", description = "재고 홀드 동시성 실험. 운영에는 등록되지 않는다")
@RestController
@RequestMapping("/dev/stock-hold")
@Profile("local")
@RequiredArgsConstructor
@Validated
public class StockHoldDemoController {

	private final StockHoldDemoService demoService;

	@PostMapping("/run")
	public DemoResult run(
			@RequestParam(defaultValue = "CONDITIONAL_UPDATE") StockHoldDemoService.Mode mode,
			@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int stock,
			@RequestParam(defaultValue = "200") @Min(1) @Max(500) int threads) {
		return demoService.run(mode, stock, threads);
	}
}
