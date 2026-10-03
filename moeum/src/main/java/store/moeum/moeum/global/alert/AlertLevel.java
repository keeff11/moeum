package store.moeum.moeum.global.alert;

/**
 * 운영 알림의 심각도 (D-068).
 *
 * <pre>
 *   WARN      봐야 하지만 지금 당장 사람이 깰 일은 아니다
 *   CRITICAL  놓치면 돈을 잃는다. 채널 전체를 부른다
 * </pre>
 */
public enum AlertLevel {

	WARN(":warning: "),

	/** {@code <!channel>} 은 채널 전원에게 푸시를 보낸다. 남발하면 아무도 안 보게 된다 */
	CRITICAL("<!channel> :rotating_light: ");

	private final String prefix;

	AlertLevel(String prefix) {
		this.prefix = prefix;
	}

	public String prefix() {
		return prefix;
	}
}
