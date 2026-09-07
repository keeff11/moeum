package store.moeum.moeum.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import store.moeum.moeum.payment.refund.EobWindow;
import store.moeum.moeum.payment.refund.RefundTax;
import store.moeum.moeum.payment.refund.RefundTaxCalculator;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세금 안분과 EOB 판정.
 *
 * 둘 다 외부 호출이 없는 순수 계산인데, <b>버그가 숨기 좋은 자리다</b> —
 * 반올림 1원과 경계 시각 1초가 각각 "영원히 전액 취소가 안 됨" 과
 * "될 취소를 막음" 으로 나타난다.
 */
class RefundTaxAndEobTest {

	@Nested
	@DisplayName("세금 안분")
	class Tax {

		/** 32,000원 결제 = 공급가 29,091 + 부가세 2,909 */
		private static final int AMOUNT = 32000;
		private static final int VAT = 2909;

		@Test
		@DisplayName("부분_취소는_원_결제_비율대로_나눈다")
		void 비율_안분() {
			RefundTax tax = RefundTaxCalculator.split(10000, AMOUNT, VAT, 0, 0, 0, 0);

			// point3 문서 예시와 같은 값이다 (10000 → vat 909)
			assertThat(tax.amount()).isEqualTo(10000);
			assertThat(tax.vat()).isEqualTo(909);
			assertThat(tax.taxFreeAmount()).isZero();
		}

		@Test
		@DisplayName("마지막_취소는_계산하지_않고_잔액을_그대로_쓴다")
		void 마지막은_잔액() {
			// 10,000 씩 세 번 취소하면 vat 는 909 × 3 = 2727. 원 부가세 2909 와 182 차이가 난다
			RefundTax first = RefundTaxCalculator.split(10000, AMOUNT, VAT, 0, 0, 0, 0);
			RefundTax second = RefundTaxCalculator.split(10000, AMOUNT, VAT, 0, 10000, first.vat(), 0);
			int refundedVat = first.vat() + second.vat();

			RefundTax last = RefundTaxCalculator.split(12000, AMOUNT, VAT, 0, 20000, refundedVat, 0);

			// 잔액을 그대로 넣어야 합계가 원 결제와 정확히 맞는다
			assertThat(last.vat()).isEqualTo(VAT - refundedVat);
			assertThat(first.vat() + second.vat() + last.vat()).isEqualTo(VAT);
			assertThat(10000 + 10000 + 12000).isEqualTo(AMOUNT);
		}

		@Test
		@DisplayName("반올림_오차가_쌓여도_1원이_남지_않는다")
		void 오차가_남지_않는다() {
			// 3원씩 나눠떨어지지 않는 금액으로 끝까지 취소해 본다
			int amount = 10000;
			int vat = 909;
			int refundedAmount = 0;
			int refundedVat = 0;

			while (refundedAmount < amount) {
				int next = Math.min(333, amount - refundedAmount);
				RefundTax tax = RefundTaxCalculator.split(next, amount, vat, 0, refundedAmount, refundedVat, 0);
				refundedAmount += tax.amount();
				refundedVat += tax.vat();
			}

			assertThat(refundedAmount).isEqualTo(amount);
			// 여기가 어긋나면 원 결제가 영영 fullyRefunded 가 되지 않는다
			assertThat(refundedVat).isEqualTo(vat);
		}

		@Test
		@DisplayName("면세_상품도_비율대로_나뉜다")
		void 면세_안분() {
			// 전액 면세 10,000원
			RefundTax tax = RefundTaxCalculator.split(4000, 10000, 0, 10000, 0, 0, 0);

			assertThat(tax.taxFreeAmount()).isEqualTo(4000);
			assertThat(tax.vat()).isZero();
		}

		@Test
		@DisplayName("잔액을_넘는_취소는_거부한다")
		void 잔액_초과() {
			assertThatThrownBy(() -> RefundTaxCalculator.split(5000, AMOUNT, VAT, 0, 30000, 0, 0))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("취소 가능 잔액");
		}

		@Test
		@DisplayName("면세와_부가세의_합이_취소액을_넘지_않는다")
		void 불변식() {
			// point3 가 거부하는 조건이다: refundTaxFreeAmount + refundVat <= refundAmount
			for (int refund = 1; refund <= AMOUNT; refund += 137) {
				RefundTax tax = RefundTaxCalculator.split(refund, AMOUNT, VAT, 0, 0, 0, 0);
				assertThat(tax.taxFreeAmount() + tax.vat())
						.as("refundAmount=%d", refund)
						.isLessThanOrEqualTo(tax.amount());
			}
		}

		@Test
		@DisplayName("전액_취소는_원_결제_구성을_그대로_쓴다")
		void 전액() {
			RefundTax tax = RefundTaxCalculator.full(AMOUNT, VAT, 0);

			assertThat(tax.amount()).isEqualTo(AMOUNT);
			assertThat(tax.vat()).isEqualTo(VAT);
		}

		@Test
		@DisplayName("0원_취소는_만들_수_없다")
		void 영원_취소() {
			assertThatThrownBy(() -> new RefundTax(0, 0, 0))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("EOB 차단 시간대")
	class Eob {

		@Test
		@DisplayName("23시_30분부터_0시_30분_전까지_막힌다")
		void 차단_구간() {
			assertThat(EobWindow.isBlocked(at(23, 29, 59))).isFalse();
			assertThat(EobWindow.isBlocked(at(23, 30, 0))).isTrue();   // 이상
			assertThat(EobWindow.isBlocked(at(23, 59, 59))).isTrue();
			assertThat(EobWindow.isBlocked(at(0, 0, 0))).isTrue();
			assertThat(EobWindow.isBlocked(at(0, 29, 59))).isTrue();
			assertThat(EobWindow.isBlocked(at(0, 30, 0))).isFalse();   // 미만
			assertThat(EobWindow.isBlocked(at(12, 0, 0))).isFalse();
		}

		@Test
		@DisplayName("자정_전이면_다음_날_0시_30분에_풀린다")
		void 자정_전() {
			LocalDateTime open = EobWindow.nextOpenAt(
					LocalDateTime.of(2026, 9, 7, 23, 40));

			assertThat(open).isEqualTo(LocalDateTime.of(2026, 9, 8, 0, 30));
		}

		@Test
		@DisplayName("자정_후면_같은_날_0시_30분에_풀린다")
		void 자정_후() {
			LocalDateTime open = EobWindow.nextOpenAt(
					LocalDateTime.of(2026, 9, 8, 0, 10));

			assertThat(open).isEqualTo(LocalDateTime.of(2026, 9, 8, 0, 30));
		}

		@Test
		@DisplayName("막히지_않은_시간이면_풀리는_시각이_없다")
		void 안_막힘() {
			assertThat(EobWindow.nextOpenAt(at(12, 0, 0))).isNull();
		}

		private static LocalDateTime at(int hour, int minute, int second) {
			return LocalDateTime.of(2026, 9, 7, hour, minute, second);
		}
	}
}
