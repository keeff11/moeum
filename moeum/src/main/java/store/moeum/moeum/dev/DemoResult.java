package store.moeum.moeum.dev;

import java.util.List;

/** 동시성 실험 결과 */
public record DemoResult(
		String mode,
		int stockMax,
		int threads,
		int success,
		int outOfStock,
		int otherError,
		int held,
		int sold,
		int oversold,
		boolean invariantHeld,
		long elapsedMs,
		List<String> notes
) {
}
