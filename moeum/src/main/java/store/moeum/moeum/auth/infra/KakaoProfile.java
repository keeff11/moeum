package store.moeum.moeum.auth.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 카카오 프로필. 닉네임만 쓴다 (api-spec 10-⑨). 수령인 이름은 별도 입력 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoProfile(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record KakaoAccount(Profile profile) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		public record Profile(String nickname) {
		}
	}

	public String kakaoId() {
		return String.valueOf(id);
	}

	public String nickname() {
		if (kakaoAccount == null || kakaoAccount.profile() == null) {
			return null;
		}
		return kakaoAccount.profile().nickname();
	}
}
