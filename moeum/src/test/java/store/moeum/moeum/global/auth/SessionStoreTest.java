package store.moeum.moeum.global.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import store.moeum.moeum.support.IntegrationTest;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세션 저장소가 톰캣 힙도 MySQL 도 아니라 Redis 라는 것 (D-067).
 *
 * 여기서 보는 것은 네 가지다.
 * 1. 저장소 구현이 Redis 인가 — 설정이 어긋나면 예외 없이 톰캣 인메모리로 돌아간다 (D-066).
 * 2. spring-session-jdbc 가 클래스패스에 없는가 — Redis 와 같이 두면 Spring Session 이 통째로 꺼진다 (D-066).
 * 3. 세션이 실제로 Redis 키로 쌓이고 30분 뒤 사라지는가.
 * 4. 저장소를 바꿔도 쿠키가 그대로인가 — 이름이 SESSION 으로 바뀌면 기존 로그인이 전부 끊긴다.
 */
class SessionStoreTest extends IntegrationTest {

	private static final String KEY_PREFIX = "moeum:session:sessions:";

	@Autowired
	private SessionRepository<? extends Session> sessionRepository;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private CookieSerializer cookieSerializer;

	@Test
	@DisplayName("세션_저장소_구현은_Redis_다")
	void 세션_저장소_구현은_Redis_다() {
		assertThat(sessionRepository)
				.as("설정이 어긋나면 조용히 톰캣 인메모리로 돌아간다. 배포마다 전원 로그아웃이 다시 시작된다")
				.isInstanceOf(RedisSessionRepository.class);
	}

	@Test
	@DisplayName("spring-session-jdbc_는_클래스패스에_없다")
	void spring_session_jdbc_는_클래스패스에_없다() {
		// 두 구현이 같이 있으면 Spring Session 자동 구성이 빠지고 세션이 톰캣 힙으로 떨어진다.
		// 예외도 경고도 없고 로컬에서는 증상도 없다 (D-066)
		assertThatThrownBy(() -> Class.forName("org.springframework.session.jdbc.JdbcIndexedSessionRepository"))
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	@DisplayName("세션은_톰캣_힙이_아니라_Redis_에_저장된다")
	void 세션은_Redis_에_저장된다() {
		SessionUser user = new SessionUser("1234567890", "모으미");
		String sessionId = save(sessionRepository, user);

		assertThat(redis.hasKey(KEY_PREFIX + sessionId)).isTrue();

		// 앱이 재시작해도 여기서 다시 읽어온다 — 이게 '배포마다 전원 로그아웃' 을 없애는 지점이다
		Session reloaded = sessionRepository.findById(sessionId);
		assertThat(reloaded).isNotNull();
		assertThat((SessionUser) reloaded.getAttribute(SessionKeys.LOGIN_USER)).isEqualTo(user);
	}

	@Test
	@DisplayName("세션_키는_30분_뒤_만료된다")
	void 세션_키는_30분_뒤_만료된다() {
		String sessionId = save(sessionRepository, new SessionUser("1234567890", "모으미"));

		// MySQL 때는 1분 크론이 지웠다. 이제 Redis TTL 이 지운다 — TTL 이 없으면 세션이 영원히 쌓인다
		Long ttl = redis.getExpire(KEY_PREFIX + sessionId, TimeUnit.SECONDS);
		assertThat(ttl).isBetween(29L * 60, 30L * 60);
	}

	@Test
	@DisplayName("세션에_담기는_속성은_로그인_주체_하나뿐이다")
	void 세션_속성은_로그인_주체_하나뿐이다() {
		String sessionId = save(sessionRepository, new SessionUser("1234567890", "모으미"));

		// 카카오 access token 을 넣지 않기로 했다 (D-020). 넣으면 여기 필드가 하나 더 생긴다
		var attributes = redis.opsForHash().keys(KEY_PREFIX + sessionId).stream()
				.map(Object::toString)
				.filter(field -> field.startsWith("sessionAttr:"))
				.toList();

		assertThat(attributes).containsExactly("sessionAttr:" + SessionKeys.LOGIN_USER);
	}

	@Test
	@DisplayName("로그아웃하면_세션_키가_지워진다")
	void 로그아웃하면_세션_키가_지워진다() {
		String sessionId = save(sessionRepository, new SessionUser("9999999999", "탈퇴"));

		sessionRepository.deleteById(sessionId);

		assertThat(redis.hasKey(KEY_PREFIX + sessionId)).isFalse();
	}

	@Test
	@DisplayName("세션_쿠키는_저장소를_바꿔도_MOEUM_SESSION_그대로다")
	void 세션_쿠키_설정이_그대로다() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		cookieSerializer.writeCookieValue(
				new CookieSerializer.CookieValue(request, response, "test-session-id"));

		Cookie cookie = response.getCookie("MOEUM_SESSION");
		assertThat(cookie).as("이름이 SESSION 으로 바뀌면 배포 즉시 기존 로그인이 전부 끊긴다").isNotNull();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getPath()).isEqualTo("/");
		assertThat(response.getHeader("Set-Cookie")).contains("SameSite=Lax");
	}

	/** 저장소 타입이 와일드카드라 캡처를 위해 제네릭 메서드로 감싼다 */
	private static <S extends Session> String save(SessionRepository<S> repository, SessionUser user) {
		S session = repository.createSession();
		session.setAttribute(SessionKeys.LOGIN_USER, user);
		repository.save(session);
		return session.getId();
	}
}
