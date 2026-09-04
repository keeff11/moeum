package store.moeum.moeum.global.auth;

import java.io.Serializable;

/**
 * 로그인 주체. 세션(서버 메모리)에만 들어가고 브라우저에는 세션 ID 쿠키만 나간다.
 *
 * 카카오 인증과 우리 서비스 인증은 별개다. 이 값은 "카카오로 신원이 확인된 사람"까지만 뜻하고,
 * 셀러인지 구매자인지는 각 도메인에서 kakaoId 로 조회한다.
 */
public record SessionUser(String kakaoId, String nickname) implements Serializable {
}
