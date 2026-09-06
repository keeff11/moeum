package store.moeum.moeum.global.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import store.moeum.moeum.support.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 저장소가 톰캣 힙이 아니라 MySQL 이라는 것 (D-020).
 *
 * 여기서 보는 것은 세 가지다.
 * 1. 저장소 구현이 JDBC 인가 — 설정이 빠지면 조용히 톰캣 인메모리로 돌아간다.
 * 2. V2__spring_session.sql 의 스키마가 라이브러리가 기대하는 것과 맞는가 —
 *    컬럼명이나 타입이 어긋나면 운영에서 로그인 시점에 처음 터진다.
 * 3. 저장소를 바꿔도 쿠키가 그대로인가 — 이름이 SESSION 으로 바뀌면 기존 로그인이 전부 끊긴다.
 */
class SessionStoreTest extends IntegrationTest {

	@Autowired
	private SessionRepository<? extends Session> sessionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CookieSerializer cookieSerializer;

	@Test
	@DisplayName("세션_저장소_구현은_JDBC_다")
	void 세션_저장소_구현은_JDBC_다() {
		assertThat(sessionRepository)
				.as("설정이 빠지면 조용히 톰캣 인메모리로 돌아간다. 배포마다 전원 로그아웃이 다시 시작된다")
				.isInstanceOf(JdbcIndexedSessionRepository.class);
	}

	@Test
	@DisplayName("세션은_톰캣_힙이_아니라_MySQL_에_저장된다")
	void 세션은_MySQL_에_저장된다() {
		SessionUser user = new SessionUser("1234567890", "모으미");
		String sessionId = save(sessionRepository, user);

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
				Integer.class, sessionId);
		assertThat(rows).isEqualTo(1);

		// 앱이 재시작해도 여기서 다시 읽어온다 — 이게 '배포마다 전원 로그아웃' 을 없애는 지점이다
		Session reloaded = sessionRepository.findById(sessionId);
		assertThat(reloaded).isNotNull();
		assertThat((SessionUser) reloaded.getAttribute(SessionKeys.LOGIN_USER)).isEqualTo(user);
	}

	@Test
	@DisplayName("세션에_담기는_속성은_로그인_주체_하나뿐이다")
	void 세션_속성은_로그인_주체_하나뿐이다() {
		String sessionId = save(sessionRepository, new SessionUser("1234567890", "모으미"));

		// 카카오 access token 을 넣지 않기로 했다 (D-020). 넣으면 여기 평문 blob 행이 하나 더 생긴다.
		var names = jdbcTemplate.queryForList(
				"SELECT ATTRIBUTE_NAME FROM SPRING_SESSION_ATTRIBUTES a "
						+ "JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID "
						+ "WHERE s.SESSION_ID = ?",
				String.class, sessionId);

		assertThat(names).containsExactly(SessionKeys.LOGIN_USER);
	}

	@Test
	@DisplayName("로그아웃하면_세션_행이_지워진다")
	void 로그아웃하면_세션_행이_지워진다() {
		String sessionId = save(sessionRepository, new SessionUser("9999999999", "탈퇴"));

		sessionRepository.deleteById(sessionId);

		Integer rows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?",
				Integer.class, sessionId);
		assertThat(rows).isZero();
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

	/** 저장소 타입이 와일드카드라 캡처를 위해 제네릭 메서드로 감싼다 (JdbcSession 은 패키지 전용 클래스다) */
	private static <S extends Session> String save(SessionRepository<S> repository, SessionUser user) {
		S session = repository.createSession();
		session.setAttribute(SessionKeys.LOGIN_USER, user);
		repository.save(session);
		return session.getId();
	}
}
