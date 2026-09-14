package store.moeum.moeum.global.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 운영자 명단. 카카오 회원번호로만 식별한다 (D-055).
 *
 * <b>테이블로 두지 않는다.</b> 운영자는 소수이고 심사는 사람이 직접 하는 방식이라
 * 목록을 관리하는 화면까지 만들 이유가 없다. 명단이 자주 바뀌거나 "누가 언제 운영자가
 * 됐는지" 를 남겨야 하면 그때 테이블로 옮긴다.
 *
 * <b>비어 있으면 아무도 운영자가 아니다.</b> 허용 출처 목록과 반대 방향으로 판단한다 —
 * 그쪽은 비어 있으면 검사를 걸러 로컬 개발을 편하게 하지만, 이쪽이 같은 식이면
 * 설정 하나 빠진 것이 곧 승인 API 전면 개방이 된다. 로컬에서 심사 API 를 만지려면
 * {@code ADMIN_KAKAO_IDS} 를 채운다.
 */
@Component
public class AdminAccounts {

	private final Set<String> kakaoIds;

	public AdminAccounts(@Value("${moeum.auth.admin-kakao-ids:}") List<String> configured) {
		this.kakaoIds = (configured == null) ? Set.of() : configured.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(String::trim)
				.collect(Collectors.toUnmodifiableSet());
	}

	/** 인터셉터처럼 빈 주입을 쓸 수 없는 자리에서 같은 설정으로 만든다 */
	public static AdminAccounts of(List<String> configured) {
		return new AdminAccounts(configured);
	}

	public boolean isAdmin(SessionUser user) {
		return user != null && contains(user.kakaoId());
	}

	public boolean contains(String kakaoId) {
		return kakaoId != null && kakaoIds.contains(kakaoId);
	}

	public boolean isEmpty() {
		return kakaoIds.isEmpty();
	}
}
