package store.moeum.moeum.buyer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.buyer.dto.NotifyPhoneResponse;
import store.moeum.moeum.buyer.dto.PhoneVerificationConfirmRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationRequest;
import store.moeum.moeum.buyer.dto.PhoneVerificationResponse;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.outbox.SmsSender;
import store.moeum.moeum.outbox.infra.SolapiFailedException;
import store.moeum.moeum.outbox.infra.SolapiUncertainException;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 받을 번호 문자 인증 (D-064).
 *
 * 확인하려는 것:
 * <ul>
 *   <li><b>틀린 횟수가 실제로 쌓인다</b> — 틀릴 때 예외로 되돌아가면 몇 번이고 찍어 볼 수 있다</li>
 *   <li><b>문자 요금을 태울 수 없다</b> — 발송 간격 · 하루 한도(구매자 · 번호 각각)</li>
 *   <li><b>발송 실패를 둘로 나눈다</b> — 확정 실패는 기록을 지우고, 결과 불명은 남긴다</li>
 *   <li><b>살아 있는 인증번호는 마지막 한 건 · 요청한 그 번호에만</b> 쓸 수 있다</li>
 * </ul>
 */
@Import({PhoneVerificationTest.FixedClockConfig.class, PhoneVerificationTest.FakeSmsConfig.class})
class PhoneVerificationTest extends IntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);
	private static final String PHONE = "010-1234-5678";
	private static final Pattern CODE = Pattern.compile("인증번호 (\\d{6})");

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}
	}

	@TestConfiguration
	static class FakeSmsConfig {
		@Bean
		@Primary
		FakeSms fakeSms() {
			return new FakeSms();
		}
	}

	/** 보낸 문자를 들고 있고, 실패를 골라서 낼 수 있다 */
	static class FakeSms implements SmsSender {
		final List<String> sent = new ArrayList<>();
		volatile RuntimeException failure;

		@Override
		public void send(String to, String text) {
			if (failure != null) {
				throw failure;
			}
			sent.add(to + "|" + text);
		}

		String lastCode() {
			Matcher matcher = CODE.matcher(sent.get(sent.size() - 1));
			assertThat(matcher.find()).isTrue();
			return matcher.group(1);
		}
	}

	@Autowired
	private PhoneVerificationService service;

	@Autowired
	private FakeSms sms;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderFixture fixture;

	@BeforeEach
	void setUp() {
		fixture.clean();
		sms.sent.clear();
		sms.failure = null;
	}

	// ---------------------------------------------------------------- 성공 경로

	@Test
	@DisplayName("요청하면_숫자만_남긴_번호로_6자리_인증번호가_간다")
	void 발송() {
		PhoneVerificationResponse response = service.send(me(), new PhoneVerificationRequest(PHONE));

		assertThat(sms.sent).hasSize(1);
		assertThat(sms.sent.get(0)).startsWith("01012345678|");
		assertThat(sms.lastCode()).hasSize(6);
		assertThat(response.expiresAt()).isEqualTo(NOW.plusMinutes(3));
		assertThat(response.resendAvailableAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	@DisplayName("단문_한_통에_들어간다")
	void 단문_길이() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String text = sms.sent.get(0).split("\\|", 2)[1];

		// 90바이트(EUC-KR 기준 한글 2바이트)를 넘으면 장문으로 바뀌어 단가가 오른다
		assertThat(text.getBytes(java.nio.charset.Charset.forName("EUC-KR")).length).isLessThanOrEqualTo(90);
	}

	@Test
	@DisplayName("맞히면_알림_받을_번호로_저장되고_가려진_번호로_조회된다")
	void 인증_성공() {
		assertThat(service.find(me())).isEmpty();

		service.send(me(), new PhoneVerificationRequest(PHONE));
		NotifyPhoneResponse confirmed = service.confirm(me(), confirm(PHONE, sms.lastCode()));

		assertThat(confirmed.phoneMasked()).isEqualTo("010-****-5678");
		assertThat(confirmed.verifiedAt()).isEqualTo(NOW);
		assertThat(service.find(me())).get()
				.extracting(NotifyPhoneResponse::phoneMasked).isEqualTo("010-****-5678");
		// 하이픈 없이 저장해야 발송 직전에 다시 손볼 일이 없다
		assertThat(jdbcTemplate.queryForObject("SELECT notify_phone FROM buyer", String.class))
				.isEqualTo("01012345678");
	}

	@Test
	@DisplayName("하이픈을_달리_적어도_같은_번호로_본다")
	void 하이픈_무관() {
		service.send(me(), new PhoneVerificationRequest("010-1234-5678"));

		assertThat(service.confirm(me(), confirm("01012345678", sms.lastCode())).phoneMasked())
				.isEqualTo("010-****-5678");
	}

	@Test
	@DisplayName("인증번호는_DB에_평문으로_남지_않는다")
	void 해시() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String code = sms.lastCode();

		String stored = jdbcTemplate.queryForObject("SELECT code_hash FROM phone_verification", String.class);
		assertThat(stored).hasSize(64).doesNotContain(code);
	}

	// ---------------------------------------------------------------- 틀림 · 만료

	@Test
	@DisplayName("틀리면_남은_기회를_알려_주고_횟수가_실제로_쌓인다")
	void 틀림() {
		service.send(me(), new PhoneVerificationRequest(PHONE));

		assertThatThrownBy(() -> service.confirm(me(), confirm(PHONE, wrong())))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("남은 기회 4번")
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.PHONE_VERIFICATION_MISMATCH);

		// 예외와 함께 트랜잭션이 되돌아가면 0 으로 남는다 — 무한히 찍어 볼 수 있게 된다
		assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM phone_verification", Integer.class))
				.isEqualTo(1);
		assertThat(service.find(me())).isEmpty();
	}

	@Test
	@DisplayName("다섯_번_틀리면_맞는_번호도_받지_않는다")
	void 잠김() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String code = sms.lastCode();

		for (int i = 0; i < 4; i++) {
			assertError(() -> service.confirm(me(), confirm(PHONE, wrong())), ErrorCode.PHONE_VERIFICATION_MISMATCH);
		}
		// 다섯 번째는 기회를 다 썼다고 알린다
		assertError(() -> service.confirm(me(), confirm(PHONE, wrong())), ErrorCode.PHONE_VERIFICATION_LOCKED);
		assertError(() -> service.confirm(me(), confirm(PHONE, code)), ErrorCode.PHONE_VERIFICATION_LOCKED);
	}

	@Test
	@DisplayName("3분이_지나면_맞아도_받지_않는다")
	void 만료() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		jdbcTemplate.update("UPDATE phone_verification SET expires_at = ?", NOW);

		assertError(() -> service.confirm(me(), confirm(PHONE, sms.lastCode())),
				ErrorCode.PHONE_VERIFICATION_EXPIRED);
	}

	@Test
	@DisplayName("요청하지_않은_번호로는_인증할_수_없다")
	void 다른_번호() {
		// 내 번호로 받은 인증번호로 남의 번호를 알림 번호로 올리는 길을 막는다
		service.send(me(), new PhoneVerificationRequest(PHONE));

		assertError(() -> service.confirm(me(), confirm("010-9999-8888", sms.lastCode())),
				ErrorCode.PHONE_VERIFICATION_NOT_FOUND);
	}

	@Test
	@DisplayName("요청한_적이_없으면_확인할_것이_없다")
	void 요청_없음() {
		assertError(() -> service.confirm(me(), confirm(PHONE, "123456")),
				ErrorCode.PHONE_VERIFICATION_NOT_FOUND);
	}

	@Test
	@DisplayName("한_번_쓴_인증번호는_다시_쓸_수_없다")
	void 재사용() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String code = sms.lastCode();
		service.confirm(me(), confirm(PHONE, code));

		assertError(() -> service.confirm(me(), confirm(PHONE, code)), ErrorCode.PHONE_VERIFICATION_NOT_FOUND);
	}

	@Test
	@DisplayName("다시_받으면_앞의_인증번호는_쓸_수_없다")
	void 마지막만_유효() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String first = sms.lastCode();
		passResendInterval();
		service.send(me(), new PhoneVerificationRequest(PHONE));
		String second = sms.lastCode();

		if (!first.equals(second)) {
			assertError(() -> service.confirm(me(), confirm(PHONE, first)), ErrorCode.PHONE_VERIFICATION_MISMATCH);
		}
		assertThat(service.confirm(me(), confirm(PHONE, second)).phoneMasked()).isEqualTo("010-****-5678");
	}

	@Test
	@DisplayName("다른_번호로_다시_인증하면_알림_번호가_바뀐다")
	void 번호_변경() {
		service.send(me(), new PhoneVerificationRequest(PHONE));
		service.confirm(me(), confirm(PHONE, sms.lastCode()));
		passResendInterval();

		service.send(me(), new PhoneVerificationRequest("011-234-5678"));
		assertThat(service.confirm(me(), confirm("011-234-5678", sms.lastCode())).phoneMasked())
				.isEqualTo("011-****-5678");
	}

	// ---------------------------------------------------------------- 요금 방어

	@Test
	@DisplayName("1분_안에_다시_받을_수_없다")
	void 발송_간격() {
		service.send(me(), new PhoneVerificationRequest(PHONE));

		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)),
				ErrorCode.PHONE_VERIFICATION_TOO_SOON);
		// 번호를 바꿔도 같다 — 구매자 기준으로 센다
		assertError(() -> service.send(me(), new PhoneVerificationRequest("010-2222-3333")),
				ErrorCode.PHONE_VERIFICATION_TOO_SOON);
		assertThat(sms.sent).hasSize(1);

		passResendInterval();
		service.send(me(), new PhoneVerificationRequest(PHONE));
		assertThat(sms.sent).hasSize(2);
	}

	@Test
	@DisplayName("구매자당_하루_다섯_번까지다")
	void 하루_한도_구매자() {
		for (int i = 0; i < 5; i++) {
			// 번호를 바꿔 가며 받아도 구매자 기준으로 막힌다
			service.send(me(), new PhoneVerificationRequest("010-1234-000" + i));
			passResendInterval();
		}

		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)),
				ErrorCode.PHONE_VERIFICATION_DAILY_LIMIT);
		assertThat(sms.sent).hasSize(5);
	}

	@Test
	@DisplayName("번호당_하루_다섯_번까지다_계정을_바꿔도")
	void 하루_한도_번호() {
		// 카카오 계정을 여럿 만들어 한 사람에게 문자를 쏟아붓는 것을 막는다
		for (int i = 0; i < 5; i++) {
			service.send(new SessionUser("kakao-phone-" + i, "구매자" + i), new PhoneVerificationRequest(PHONE));
		}

		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)),
				ErrorCode.PHONE_VERIFICATION_DAILY_LIMIT);
	}

	@Test
	@DisplayName("어제_받은_것은_오늘_한도에_세지_않는다")
	void 하루_경계() {
		for (int i = 0; i < 5; i++) {
			service.send(me(), new PhoneVerificationRequest(PHONE));
			passResendInterval();
		}
		jdbcTemplate.update("UPDATE phone_verification SET created_at = ?", NOW.toLocalDate().minusDays(1).atTime(23, 59));

		service.send(me(), new PhoneVerificationRequest(PHONE));
		assertThat(sms.sent).hasSize(6);
	}

	// ---------------------------------------------------------------- 발송 실패

	@Test
	@DisplayName("확정_실패면_기록을_지워_바로_다시_받을_수_있다")
	void 확정_실패() {
		sms.failure = new SolapiFailedException("SOLAPI 4xx: 400 BAD_REQUEST");

		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)), ErrorCode.SMS_SEND_FAILED);
		assertThat(rows()).isZero();

		// 우리 쪽이 못 보낸 것으로 구매자가 1분을 기다리거나 하루 기회를 잃으면 안 된다
		sms.failure = null;
		service.send(me(), new PhoneVerificationRequest(PHONE));
		assertThat(sms.sent).hasSize(1);
	}

	@Test
	@DisplayName("결과_불명이면_기록을_남겨_늦게_온_인증번호도_쓸_수_있다")
	void 결과_불명() {
		// 실제로는 나갔을 수 있다. 지우면 늦게 도착한 문자가 쓸모없어진다 (CLAUDE.md 규칙 3 과 같은 판단)
		sms.failure = new SolapiUncertainException("SOLAPI 5xx: 503");

		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)), ErrorCode.SMS_SEND_UNCERTAIN);
		assertThat(rows()).isEqualTo(1);

		// 같은 번호로 다시 부르면 간격에 걸린다 — 두 통이 한꺼번에 도착하는 것을 막는다
		assertError(() -> service.send(me(), new PhoneVerificationRequest(PHONE)),
				ErrorCode.PHONE_VERIFICATION_TOO_SOON);
	}

	@Test
	@DisplayName("결과_불명으로_남은_기록의_인증번호로_인증된다")
	void 결과_불명_후_인증() {
		// 발송기가 본문을 받은 뒤 결과 불명으로 끝난 상황 — 문자는 실제로 도착했다
		SmsSender lateArrival = (to, text) -> {
			sms.sent.add(to + "|" + text);
			throw new SolapiUncertainException("SOLAPI 호출 실패");
		};
		PhoneVerificationService late = new PhoneVerificationService(writer, buyerService, lateArrival);

		assertError(() -> late.send(me(), new PhoneVerificationRequest(PHONE)), ErrorCode.SMS_SEND_UNCERTAIN);

		assertThat(service.confirm(me(), confirm(PHONE, sms.lastCode())).phoneMasked())
				.isEqualTo("010-****-5678");
	}

	// ---------------------------------------------------------------- 도구

	@Autowired
	private PhoneVerificationWriter writer;

	@Autowired
	private BuyerService buyerService;

	/** 발송 간격을 지난 것으로 만든다. 시계는 고정이라 기록 쪽을 과거로 민다 */
	private void passResendInterval() {
		jdbcTemplate.update("UPDATE phone_verification SET created_at = created_at - INTERVAL 61 SECOND");
	}

	private String wrong() {
		String code = sms.lastCode();
		return code.equals("000000") ? "111111" : "000000";
	}

	private static PhoneVerificationConfirmRequest confirm(String phone, String code) {
		return new PhoneVerificationConfirmRequest(phone, code);
	}

	private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
		assertThatThrownBy(call)
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(expected);
	}

	private Integer rows() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_verification", Integer.class);
	}

	private static SessionUser me() {
		return new SessionUser("kakao-phone-me", "김서연");
	}
}
