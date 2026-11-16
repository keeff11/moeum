package store.moeum.moeum.global.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요청이 끝날 때 하는 세션 저장(마지막 접근 시각 갱신)이 Redis 장애로 실패하는 경우.
 *
 * Spring Session 은 응답을 내보내는 순간 세션을 저장한다. 그때는 주문이 이미 커밋돼 있다.
 * 이 저장 실패가 500 으로 번지면 주문 · 홀드는 남는데 구매자는 실패를 본다.
 *
 * 그래서 <b>로그인 연장만 하는 저장</b>은 실패해도 넘기고, <b>새 세션 · 속성이 바뀐 세션</b>은
 * 지금처럼 실패시킨다 — 로그인 응답이 성공인데 세션이 저장되지 않았으면 로그인된 척하는 것이다.
 *
 * Redis 장애는 {@code CLIENT PAUSE ... WRITE} 로 만든다. 읽기는 그대로 되고 쓰기만 멈춘다 —
 * 요청 초반의 세션 읽기는 성공하고, 끝의 세션 저장만 명령 타임아웃(2초)에 걸린다.
 * pause 는 다른 클라이언트(redis-cli)로 걸고 푼다. 앱의 Lettuce 연결은 멈춘 명령 뒤에 줄을 서기 때문이다.
 */
class SessionSaveFailureTest extends IntegrationTest {

	private static final String BUYER = "kakao-session-fail";

	/** 앱의 명령 타임아웃(2초)보다 길게 멈춰야 저장이 실패한다 */
	private static final String PAUSE_MILLIS = "3000";

	@Autowired
	private SessionRepository<? extends Session> sessionRepository;

	@Autowired
	@Qualifier("springSessionRepositoryFilter")
	private Filter sessionFilter;

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private OrderFixture fixture;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		fixture.clean();
		// 실제 요청처럼 쿠키로 세션을 찾고, 응답을 쓸 때 Redis 에 저장하게 한다
		mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sessionFilter).build();
	}

	@AfterEach
	void unpause() throws Exception {
		REDIS.execInContainer("redis-cli", "CLIENT", "UNPAUSE");
	}

	@Test
	@DisplayName("주문이_커밋된_뒤_세션_연장이_실패해도_201_이다")
	void 주문_뒤_세션_연장_실패() throws Exception {
		OrderFixture.Setup setup = fixture.saleForm(10, null);
		String sessionId = login(sessionRepository, new SessionUser(BUYER, "구매자"));

		pauseWrites();
		mockMvc.perform(post("/checkout-sessions")
						.cookie(sessionCookie(sessionId))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"items":[{"optionId":%d,"qty":1}]}""".formatted(setup.optionId())))
				.andExpect(status().isCreated());

		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_group", Integer.class))
				.as("응답과 장부가 같은 이야기를 한다 — 주문은 하나 생겼다")
				.isEqualTo(1);

		unpause();
		// 연장만 못 했을 뿐 로그인은 그대로다. 남은 TTL 안에서는 다음 요청도 로그인 상태다
		assertThat(sessionRepository.findById(sessionId)).isNotNull();
	}

	@Test
	@DisplayName("새로_만든_세션의_저장_실패는_그대로_던진다")
	void 새_세션_저장_실패() throws Exception {
		pauseWrites();

		// 로그인 콜백이 이 경로다. 넘기면 302 로 로그인 성공을 알리고 세션은 Redis 에 없다
		assertThatThrownBy(() -> login(sessionRepository, new SessionUser(BUYER, "구매자")))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("속성을_바꾼_세션의_저장_실패는_그대로_던진다")
	void 속성_변경_저장_실패() throws Exception {
		String sessionId = login(sessionRepository, new SessionUser(BUYER, "구매자"));

		pauseWrites();

		assertThatThrownBy(() -> changeUser(sessionRepository, sessionId, new SessionUser(BUYER, "새 닉네임")))
				.isInstanceOf(RuntimeException.class);
	}

	private static void pauseWrites() throws Exception {
		var result = REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", PAUSE_MILLIS, "WRITE");
		assertThat(result.getStdout()).contains("OK");
	}

	/** DefaultCookieSerializer 는 세션 id 를 Base64 로 담는다 */
	private static Cookie sessionCookie(String sessionId) {
		return new Cookie("MOEUM_SESSION",
				Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
	}

	/** 저장소 타입이 와일드카드라 캡처를 위해 제네릭 메서드로 감싼다 */
	private static <S extends Session> String login(SessionRepository<S> repository, SessionUser user) {
		S session = repository.createSession();
		session.setAttribute(SessionKeys.LOGIN_USER, user);
		repository.save(session);
		return session.getId();
	}

	private static <S extends Session> void changeUser(SessionRepository<S> repository, String sessionId,
	                                                  SessionUser user) {
		S session = repository.findById(sessionId);
		session.setAttribute(SessionKeys.LOGIN_USER, user);
		repository.save(session);
	}
}
