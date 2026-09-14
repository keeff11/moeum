package store.moeum.moeum.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;

/**
 * {@code /admin/**} 접근 제어 (D-055).
 *
 * <b>경로로 막는다. 컨트롤러 파라미터로 막지 않는다.</b> {@code @LoginUser} 처럼
 * 시그니처에 선언하는 방식이면 새 심사 엔드포인트를 추가하면서 빠뜨릴 수 있고,
 * 그 순간 인증 없는 승인 API 가 열린다. 경로 단위로 걸면 빠뜨릴 자리가 없다.
 *
 * 로그인하지 않았으면 401, 로그인했지만 명단에 없으면 403 이다. 둘을 나누는 이유는
 * 프론트가 "로그인하세요" 와 "권한이 없습니다" 를 다르게 보여줘야 해서다.
 */
@Slf4j
@RequiredArgsConstructor
public class AdminOnlyInterceptor implements HandlerInterceptor {

	private final AdminAccounts adminAccounts;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		HttpSession session = request.getSession(false);
		Object user = (session == null) ? null : session.getAttribute(SessionKeys.LOGIN_USER);

		if (!(user instanceof SessionUser sessionUser)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (!adminAccounts.isAdmin(sessionUser)) {
			log.warn("운영자 아닌 사용자의 심사 API 접근: method={}, uri={}",
					request.getMethod(), request.getRequestURI());
			throw new BusinessException(ErrorCode.ADMIN_ONLY);
		}
		return true;
	}
}
