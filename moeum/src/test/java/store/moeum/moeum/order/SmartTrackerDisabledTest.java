package store.moeum.moeum.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.order.infra.SmartTrackerClient;
import store.moeum.moeum.order.infra.SmartTrackerProperties;
import store.moeum.moeum.order.infra.TrackingException;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 꺼져 있을 때 정말 안 나가는가 (D-048).
 *
 * <b>무료 이용권이 월 100건이다.</b> 켤 시점을 고르려고 키와 별개인 스위치를 뒀는데,
 * 그 스위치가 실제로 호출을 막지 못하면 아무 의미가 없다 — 여기서 보는 것은
 * <b>요청이 한 건도 나가지 않는가</b> 하나다.
 *
 * 스프링을 띄우지 않는다. 막는 지점이 클라이언트 안이라 컨텍스트가 필요 없고,
 * 테스트 클래스마다 컨텍스트가 하나씩 늘면 커넥션을 잡아먹는다.
 */
class SmartTrackerDisabledTest {

	private static final WireMockServer SERVER = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		SERVER.start();
	}

	@AfterEach
	void tearDown() {
		SERVER.resetAll();
	}

	@Test
	@DisplayName("키가_있어도_스위치가_꺼져_있으면_한_건도_안_나간다")
	void 꺼져_있으면_안_나간다() {
		SmartTrackerClient client = clientWith(false);

		assertThat(client.isEnabled()).isFalse();
		assertThatThrownBy(() -> client.track("04", "123456789012"))
				.isInstanceOf(TrackingException.class);
		assertThatThrownBy(client::carriers)
				.isInstanceOf(TrackingException.class);

		// 이게 깨지면 스위치가 아무것도 막지 못한 것이다
		SERVER.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	@DisplayName("스위치를_안_적으면_꺼진_것으로_본다")
	void 기본값은_꺼짐() {
		// 켜는 것은 명시적인 행동이어야 한다. 키를 넣었다고 나가기 시작하면 안 된다
		SmartTrackerClient client = clientWith(null);

		assertThat(client.isEnabled()).isFalse();
	}

	@Test
	@DisplayName("스위치를_켜도_키가_없으면_안_나간다")
	void 키가_없으면_안_나간다() {
		SmartTrackerClient client = new SmartTrackerClient(new SmartTrackerProperties(
				true, SERVER.baseUrl(), null, null, null, null, null, null));

		assertThat(client.isEnabled()).isFalse();
		assertThatThrownBy(() -> client.track("04", "123456789012"))
				.isInstanceOf(TrackingException.class);
		SERVER.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	@DisplayName("우리_쪽_사정은_구매자에게_보이는_문구로_주지_않는다")
	void 내부_사유는_감춘다() {
		// 꺼져 있다는 것은 구매자가 읽을 문구가 아니다. 내부 사유가 응답에 실리면 안 된다
		SmartTrackerClient client = clientWith(false);

		assertThatThrownBy(() -> client.track("04", "123456789012"))
				.isInstanceOf(TrackingException.class)
				.satisfies(e -> assertThat(((TrackingException) e).userMessage()).isEmpty());
	}

	private SmartTrackerClient clientWith(Boolean enabled) {
		return new SmartTrackerClient(new SmartTrackerProperties(
				enabled, SERVER.baseUrl(), "test-key", null, null, null, null, null));
	}
}
