package store.moeum.moeum.buyer.dto;

/** 휴대폰 번호 형식 */
final class PhonePattern {

	/**
	 * 010 은 가운데가 항상 네 자리다. 011 · 016~019 는 옛 번호라 세 자리도 있다.
	 * 012 · 013 · 014 · 015 는 휴대폰 앞자리가 아니다 — 문자를 보내 봐야 요금만 나간다.
	 */
	static final String MOBILE = "^(010-?[0-9]{4}|01[16789]-?[0-9]{3,4})-?[0-9]{4}$";

	private PhonePattern() {
	}
}
