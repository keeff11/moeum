package store.moeum.moeum.global.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 로그인 연장만 하는 세션 저장은 실패해도 요청을 실패시키지 않는다 (D-072).
 *
 * Spring Session 은 응답을 내보낼 때 세션(마지막 접근 시각)을 저장한다. 그때는 주문 · 결제가 이미
 * 커밋돼 있다. 이 저장이 Redis 장애(재시작 · 타임아웃 · 연결 끊김)로 실패하면 예외가
 * {@code GlobalExceptionHandler} 까지 올라가 500 이 나간다 — 주문은 남았는데 구매자는 실패를 본다.
 *
 * 그래서 저장을 두 가지로 나눈다.
 * <ul>
 *   <li><b>연장만 하는 저장</b> — 기존 세션이고 속성 · id 가 그대로다. 실패해도 잃는 것은 30분 연장뿐이다.
 *       경고 로그만 남기고 넘긴다. 세션은 남은 TTL 동안 그대로 살아 있다</li>
 *   <li><b>내용이 바뀐 저장</b> — 새 세션(로그인 · 세션 고정 방지), 속성 변경, id 변경.
 *       넘기면 로그인 응답은 성공인데 세션이 Redis 에 없게 된다. 지금처럼 예외를 던진다</li>
 * </ul>
 *
 * 저장소는 {@code SessionConfig} 의 BeanPostProcessor 가 Spring Boot 가 만든 {@code RedisSessionRepository} 를
 * 감싸서 끼운다. 설정(namespace · 만료 · flush 방식)은 원래 저장소의 것을 그대로 쓴다.
 */
@Slf4j
public final class TouchTolerantSessionRepository<S extends Session>
		implements SessionRepository<TouchTolerantSessionRepository.TrackedSession<S>> {

	private final SessionRepository<S> delegate;

	private TouchTolerantSessionRepository(SessionRepository<S> delegate) {
		this.delegate = delegate;
	}

	public static <S extends Session> TouchTolerantSessionRepository<S> wrap(SessionRepository<S> delegate) {
		return new TouchTolerantSessionRepository<>(delegate);
	}

	/** 감싼 원래 저장소. 설정이 Redis 로 잡혔는지 테스트가 확인할 때 쓴다 */
	public SessionRepository<S> delegate() {
		return delegate;
	}

	@Override
	public TrackedSession<S> createSession() {
		return new TrackedSession<>(delegate.createSession(), true);
	}

	@Override
	public void save(TrackedSession<S> session) {
		try {
			delegate.save(session.delegate);
		} catch (RuntimeException e) {
			if (!session.touchOnly()) {
				throw e;
			}
			// 세션 id 는 남기지 않는다 — 쿠키 값이라 로그만으로 로그인을 가로챌 수 있다
			log.warn("세션 연장 저장 실패 — 요청은 그대로 성공시킨다: {}: {}",
					e.getClass().getSimpleName(), e.getMessage());
			return;
		}
		session.markSaved();
	}

	@Override
	public TrackedSession<S> findById(String id) {
		S found = delegate.findById(id);
		return (found == null) ? null : new TrackedSession<>(found, false);
	}

	@Override
	public void deleteById(String id) {
		delegate.deleteById(id);
	}

	/**
	 * 저장할 내용이 연장뿐인지 기억하는 세션.
	 *
	 * 마지막 접근 시각 갱신({@link #setLastAccessedTime})만 연장으로 친다. 만료 시간 변경은 지금 쓰는 곳이 없어
	 * 보수적으로 "내용 변경" 으로 둔다.
	 */
	public static final class TrackedSession<S extends Session> implements Session {

		private final S delegate;
		private boolean isNew;
		private boolean changed;

		private TrackedSession(S delegate, boolean isNew) {
			this.delegate = delegate;
			this.isNew = isNew;
		}

		boolean touchOnly() {
			return !isNew && !changed;
		}

		/** 한 요청에서 저장이 두 번 불릴 수 있다(응답 커밋 때 · 필터 종료 때). 저장된 변경은 다시 세지 않는다 */
		private void markSaved() {
			isNew = false;
			changed = false;
		}

		@Override
		public String getId() {
			return delegate.getId();
		}

		@Override
		public String changeSessionId() {
			changed = true;
			return delegate.changeSessionId();
		}

		@Override
		public <T> T getAttribute(String attributeName) {
			return delegate.getAttribute(attributeName);
		}

		@Override
		public Set<String> getAttributeNames() {
			return delegate.getAttributeNames();
		}

		@Override
		public void setAttribute(String attributeName, Object attributeValue) {
			changed = true;
			delegate.setAttribute(attributeName, attributeValue);
		}

		@Override
		public void removeAttribute(String attributeName) {
			changed = true;
			delegate.removeAttribute(attributeName);
		}

		@Override
		public Instant getCreationTime() {
			return delegate.getCreationTime();
		}

		@Override
		public void setLastAccessedTime(Instant lastAccessedTime) {
			delegate.setLastAccessedTime(lastAccessedTime);
		}

		@Override
		public Instant getLastAccessedTime() {
			return delegate.getLastAccessedTime();
		}

		@Override
		public void setMaxInactiveInterval(Duration interval) {
			changed = true;
			delegate.setMaxInactiveInterval(interval);
		}

		@Override
		public Duration getMaxInactiveInterval() {
			return delegate.getMaxInactiveInterval();
		}

		@Override
		public boolean isExpired() {
			return delegate.isExpired();
		}
	}
}
