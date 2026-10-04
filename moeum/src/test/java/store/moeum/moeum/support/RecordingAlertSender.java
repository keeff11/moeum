package store.moeum.moeum.support;

import store.moeum.moeum.global.alert.AlertLevel;
import store.moeum.moeum.global.alert.AlertSender;

import java.util.ArrayList;
import java.util.List;

/** 보낸 알림을 모아 두는 가짜. {@code succeed = false} 면 Slack 이 끊긴 것처럼 실패한다 */
public class RecordingAlertSender implements AlertSender {

	public final List<Sent> sent = new ArrayList<>();
	public boolean succeed = true;

	@Override
	public synchronized boolean send(AlertLevel level, String text) {
		if (succeed) {
			sent.add(new Sent(level, text));
		}
		return succeed;
	}

	public synchronized void clear() {
		sent.clear();
		succeed = true;
	}

	public record Sent(AlertLevel level, String text) {
	}
}
