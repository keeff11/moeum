package store.moeum.moeum.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그인 후 복귀 지점 판정.
 *
 * 프론트(www · studio.moeum.store)와 API(api.moeum.store)가 다른 호스트라
 * 상대 경로만 허용하면 API 호스트로 돌아가 빈 화면이 된다.
 * 그렇다고 아무 절대 URL 이나 받으면 열린 리다이렉트가 된다.
 */
class ReturnToTest {

	private static final List<String> ORIGINS =
			List.of("https://www.moeum.store", "https://studio.moeum.store");

	private OAuthCookies withOrigins() {
		return new OAuthCookies(true, ORIGINS);
	}

	private OAuthCookies withoutOrigins() {
		return new OAuthCookies(false, List.of());
	}

	@Nested
	@DisplayName("허용 출처가 설정된 운영 구성")
	class Configured {

		@Test
		@DisplayName("허용 출처의 절대 URL 은 그대로 쓴다")
		void 허용_출처의_절대_URL_은_그대로_쓴다() {
			assertThat(withOrigins().sanitizeReturnTo("https://www.moeum.store/cart"))
					.isEqualTo("https://www.moeum.store/cart");
		}

		@Test
		@DisplayName("판매자 사이트로도 돌아갈 수 있다")
		void 판매자_사이트로도_돌아갈_수_있다() {
			assertThat(withOrigins().sanitizeReturnTo("https://studio.moeum.store/sale-forms/1"))
					.isEqualTo("https://studio.moeum.store/sale-forms/1");
		}

		@Test
		@DisplayName("상대 경로는 첫 허용 출처에 붙인다")
		void 상대_경로는_첫_허용_출처에_붙인다() {
			assertThat(withOrigins().sanitizeReturnTo("/checkout"))
					.isEqualTo("https://www.moeum.store/checkout");
		}

		@Test
		@DisplayName("값이 없으면 첫 허용 출처의 루트로 보낸다")
		void 값이_없으면_첫_허용_출처의_루트로_보낸다() {
			assertThat(withOrigins().sanitizeReturnTo(null)).isEqualTo("https://www.moeum.store/");
			assertThat(withOrigins().sanitizeReturnTo("  ")).isEqualTo("https://www.moeum.store/");
		}

		@Test
		@DisplayName("허용되지 않은 출처는 기본값으로 돌린다")
		void 허용되지_않은_출처는_기본값으로_돌린다() {
			OAuthCookies cookies = withOrigins();
			// 접미사만 맞춘 도메인은 남의 것이다
			assertThat(cookies.sanitizeReturnTo("https://www.moeum.store.evil.com/steal"))
					.isEqualTo("https://www.moeum.store/");
			assertThat(cookies.sanitizeReturnTo("https://evil.com"))
					.isEqualTo("https://www.moeum.store/");
			// 스킴이 다르면 다른 출처다
			assertThat(cookies.sanitizeReturnTo("http://www.moeum.store/cart"))
					.isEqualTo("https://www.moeum.store/");
		}

		@Test
		@DisplayName("프로토콜 상대 URL 과 역슬래시는 막는다")
		void 프로토콜_상대_URL_과_역슬래시는_막는다() {
			OAuthCookies cookies = withOrigins();
			assertThat(cookies.sanitizeReturnTo("//evil.com")).isEqualTo("https://www.moeum.store/");
			assertThat(cookies.sanitizeReturnTo("/\\evil.com")).isEqualTo("https://www.moeum.store/");
			assertThat(cookies.sanitizeReturnTo("https://www.moeum.store\\@evil.com"))
					.isEqualTo("https://www.moeum.store/");
		}

		@Test
		@DisplayName("javascript 스킴은 막는다")
		void javascript_스킴은_막는다() {
			assertThat(withOrigins().sanitizeReturnTo("javascript:alert(1)"))
					.isEqualTo("https://www.moeum.store/");
		}
	}

	@Nested
	@DisplayName("허용 출처가 없는 로컬 구성")
	class NotConfigured {

		@Test
		@DisplayName("상대 경로를 그대로 쓴다 — 프론트와 API 가 같은 출처다")
		void 상대_경로를_그대로_쓴다() {
			assertThat(withoutOrigins().sanitizeReturnTo("/checkout")).isEqualTo("/checkout");
		}

		@Test
		@DisplayName("외부 주소는 루트로 보낸다")
		void 외부_주소는_루트로_보낸다() {
			OAuthCookies cookies = withoutOrigins();
			assertThat(cookies.sanitizeReturnTo("https://evil.com")).isEqualTo("/");
			assertThat(cookies.sanitizeReturnTo("//evil.com")).isEqualTo("/");
			assertThat(cookies.sanitizeReturnTo(null)).isEqualTo("/");
		}
	}
}
