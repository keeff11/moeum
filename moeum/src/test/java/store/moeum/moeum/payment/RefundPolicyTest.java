package store.moeum.moeum.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import store.moeum.moeum.order.domain.Order;
import store.moeum.moeum.order.domain.OrderStatus;
import store.moeum.moeum.payment.refund.RefundPolicy;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 구매자가 스스로 취소할 수 있는 구간 (D-025).
 *
 * <b>컷을 한 칸 잘못 잡으면 돈 문제가 된다</b> — 늦게 잡으면 셀러가 이미 발주한 수량이 빠지고,
 * 이르게 잡으면 될 취소를 막아 CS 로 돌아온다. 그래서 경계마다 못을 박아 둔다.
 */
class RefundPolicyTest {

	@Nested
	@DisplayName("공동구매")
	class Group {

		@Test
		@DisplayName("마감까지는_취소할_수_있다")
		void 마감까지() {
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.PAID))).isTrue();
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.RECRUITING))).isTrue();
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.CLOSED))).isTrue();
		}

		@Test
		@DisplayName("발주가_시작되면_취소할_수_없다")
		void 발주_이후() {
			// 셀러가 이미 그 수량으로 발주했다. 여기서 빠지면 셀러가 손해를 본다
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.PRODUCING))).isFalse();
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.ARRIVED))).isFalse();
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.SHIPPED))).isFalse();
		}

		@Test
		@DisplayName("차단_사유는_사용자에게_보여도_되는_문장이다")
		void 사유_문구() {
			assertThat(RefundPolicy.blockReason(order(SaleType.GROUP, OrderStatus.PRODUCING)))
					.contains("발주")
					.doesNotContain("PRODUCING");
		}
	}

	@Nested
	@DisplayName("단독판매")
	class Solo {

		@Test
		@DisplayName("발송_직전까지_취소할_수_있다")
		void 발송_전까지() {
			// 있는 재고를 파는 것이라 발주 개념이 없다. 나가기 전이면 되돌릴 수 있다
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.PAID))).isTrue();
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.PRODUCING))).isTrue();
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.ARRIVED))).isTrue();
		}

		@Test
		@DisplayName("발송되면_취소할_수_없다")
		void 발송_이후() {
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.SHIPPED))).isFalse();
		}

		@Test
		@DisplayName("공동구매보다_늦게까지_열려_있다")
		void 공구보다_넓다() {
			// 같은 상태인데 판매 유형만으로 갈린다 — 이게 이 정책의 핵심이다
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.PRODUCING))).isTrue();
			assertThat(RefundPolicy.cancelable(order(SaleType.GROUP, OrderStatus.PRODUCING))).isFalse();
		}
	}

	@Nested
	@DisplayName("공통")
	class Common {

		@Test
		@DisplayName("결제_전_주문은_취소_대상이_아니다")
		void 결제_전() {
			// 취소가 아니라 홀드 해제로 처리할 일이다
			assertThat(RefundPolicy.blockReason(order(SaleType.GROUP, OrderStatus.CREATED)))
					.contains("결제되지 않은");
		}

		@Test
		@DisplayName("이미_취소되거나_만료된_주문은_다시_취소할_수_없다")
		void 종료된_주문() {
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.CANCELED))).isFalse();
			assertThat(RefundPolicy.cancelable(order(SaleType.SOLO, OrderStatus.EXPIRED))).isFalse();
		}
	}

	private static Order order(SaleType type, OrderStatus status) {
		Order order = Order.create(SaleForm.builder().saleType(type).build());
		// 발주·배송 상태는 아직 쓰기 경로가 없다. 정책은 그보다 먼저 확정해 둬야 한다
		ReflectionTestUtils.setField(order, "status", status);
		return order;
	}
}
