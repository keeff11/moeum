package store.moeum.moeum.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import store.moeum.moeum.order.infra.SmartTrackerClient;
import store.moeum.moeum.order.infra.SmartTrackerProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 스마트택배로 조회를 한 번 날린다. <b>이용권 횟수를 한 번 쓴다.</b>
 *
 * 그래서 평소에는 돌지 않는다 — {@code SMART_TRACKER_API_KEY} 가 있을 때만 켜진다.
 * WireMock 테스트가 검증하지 <b>못하는 것</b>을 확인하려고 남겨 둔다.
 *
 * <ul>
 *   <li><b>엔드포인트와 파라미터 이름이 맞는지</b> — 명세는 Swagger(v2/api-docs)에서 읽었고
 *       WireMock 은 우리가 적은 대로 응답할 뿐이라, 틀려도 테스트는 통과한다</li>
 *   <li><b>택배사 목록의 실제 감싸는 모양</b> — {@code {"Company":[...]}} 인지 배열인지.
 *       클라이언트는 둘 다 받게 해 뒀지만 어느 쪽인지는 여기서만 알 수 있다</li>
 *   <li>발급받은 키가 그 계정에서 실제로 통하는지</li>
 * </ul>
 *
 * 돌리는 법 (PowerShell):
 * <pre>
 *   $env:SMART_TRACKER_API_KEY="발급받은키"
 *   # 송장 조회까지 보려면 (택배사 코드 · 송장번호는 아무 실제 택배 건이면 된다)
 *   $env:SMART_TRACKER_SMOKE_CODE="04"
 *   $env:SMART_TRACKER_SMOKE_INVOICE="123456789012"
 *   ./gradlew.bat test --tests '*SmartTrackerSmokeTest*'
 * </pre>
 *
 * <b>키를 저장소에 적지 않는다</b> — 환경변수로만 넣는다 (D-017).
 */
@EnabledIfEnvironmentVariable(named = "SMART_TRACKER_API_KEY", matches = ".+")
class SmartTrackerSmokeTest {

	private final SmartTrackerClient client = new SmartTrackerClient(
			new SmartTrackerProperties(null, System.getenv("SMART_TRACKER_API_KEY"),
					null, null, null, null, null));

	@Test
	@DisplayName("택배사_목록이_실제로_내려온다")
	void 택배사_목록() {
		List<SmartTrackerClient.Carrier> carriers = client.carriers();

		// 비어 있으면 감싸는 키 이름을 잘못 읽고 있다는 뜻이다
		assertThat(carriers).isNotEmpty();
		carriers.stream().limit(5).forEach(carrier ->
				System.out.println("  " + carrier.code() + " = " + carrier.name()));
		System.out.println("택배사 " + carriers.size() + "곳");
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "SMART_TRACKER_SMOKE_INVOICE", matches = ".+")
	@DisplayName("실제_송장을_조회한다")
	void 송장_조회() {
		SmartTrackerClient.Tracking tracking = client.track(
				System.getenv("SMART_TRACKER_SMOKE_CODE"),
				System.getenv("SMART_TRACKER_SMOKE_INVOICE"));

		System.out.println("level=" + tracking.level() + ", 완료=" + tracking.completed());
		tracking.steps().forEach(step ->
				System.out.println("  " + step.time() + " " + step.where() + " " + step.kind()));

		assertThat(tracking.steps()).isNotEmpty();
	}
}
