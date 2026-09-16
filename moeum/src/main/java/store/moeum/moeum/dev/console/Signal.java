package store.moeum.moeum.dev.console;

/**
 * 개요 화면 맨 위에 뜨는 판정 한 줄.
 *
 * 숫자만 늘어놓으면 "이 값이 이상한 건가?" 를 사람이 매번 판단해야 한다.
 * 임계값을 서버가 알고 있으니 판정도 서버가 한다.
 */
public record Signal(
		Level level,
		String title,
		String detail,
		String hint
) {

	public enum Level {
		/** 돈이 걸린 문제. 지금 본다 */
		RISK,
		/** 배치가 밀렸거나 곧 문제가 될 상태 */
		WARN,
		/** 알아 두면 좋은 정보 */
		INFO
	}

	public static Signal risk(String title, String detail, String hint) {
		return new Signal(Level.RISK, title, detail, hint);
	}

	public static Signal warn(String title, String detail, String hint) {
		return new Signal(Level.WARN, title, detail, hint);
	}

	public static Signal info(String title, String detail, String hint) {
		return new Signal(Level.INFO, title, detail, hint);
	}
}
