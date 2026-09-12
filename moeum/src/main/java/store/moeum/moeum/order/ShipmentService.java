package store.moeum.moeum.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.order.dto.ShipmentRequest;
import store.moeum.moeum.order.dto.ShipmentResponse;
import store.moeum.moeum.outbox.OutboxRecorder;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
import store.moeum.moeum.seller.SellerService;
import store.moeum.moeum.seller.domain.Seller;

import java.time.LocalDateTime;
import java.util.Map;

import static store.moeum.moeum.global.jpa.JpaAuditingConfig.KST;

/**
 * 송장 등록 (와이어프레임 S12 · D-047).
 *
 * <b>이게 없어서 비어 있던 자리가 셋이다.</b> 셀러 주문 목록의 발송 완료 탭이 항상 0건이었고
 * (SHIPPED 로 올리는 코드가 없었다), 알림톡 3번(발송 완료)이 적재될 지점이 없었고,
 * 구매자는 송장번호를 받을 방법이 없었다.
 *
 * <b>송장은 묶음당 하나다.</b> {@code shipping} 이 {@code order_group} 과 1:1 이고
 * (uk_shipping_group), 배송비도 묶음당 1회라 한 번에 보내는 것이 전제다. 묶음에 폼이
 * 여럿이어도 2차금은 전 폼이 입고된 뒤에야 열리므로, 발송 시점에는 이미 다 모여 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {

	private final OrderGroupRepository orderGroupRepository;
	private final ShippingRepository shippingRepository;
	private final OutboxRecorder outboxRecorder;
	private final SellerService sellerService;

	/**
	 * 송장을 등록하거나 고친다.
	 *
	 * <b>다시 불러도 안전하다.</b> 이미 발송 처리된 묶음이면 번호만 갈아 끼우고 상태는
	 * 건드리지 않는다 — 셀러가 송장번호를 잘못 적는 일은 실제로 있고, 고칠 방법이 없으면
	 * 구매자가 엉뚱한 배송을 조회하게 된다.
	 *
	 * <b>알림은 처음 등록할 때만 나간다.</b> 번호를 고칠 때마다 "발송했습니다" 가 다시 가면
	 * 구매자는 두 번 보낸 줄 안다. {@code markShipped()} 의 반환값이 그 가드다.
	 */
	@Transactional
	public ShipmentResponse register(String kakaoId, String orderNo, ShipmentRequest request) {
		Seller seller = sellerService.getByKakaoId(kakaoId);
		OrderGroup group = ownedGroup(seller, orderNo);

		if (!group.canRegisterShipment()) {
			// 잔금을 아직 못 받았거나 입고 전이다. 여기서 막지 않으면 받을 돈이 남은 채로 물건이 나간다
			throw new BusinessException(ErrorCode.NOT_READY_TO_SHIP);
		}

		Shipping shipping = shippingRepository.findByOrderGroupId(group.getId())
				.orElseThrow(() -> new BusinessException(ErrorCode.SHIPPING_ADDRESS_REQUIRED));

		boolean first = group.markShipped();
		shipping.registerShipment(request.carrier().trim(), blankToNull(request.carrierCode()),
				request.trackingNo().trim(), LocalDateTime.now(KST));

		if (first) {
			notifyShipped(group, shipping);
			log.info("발송 처리: orderNo={}, carrier={}", orderNo, shipping.getCarrier());
		} else {
			log.info("송장 수정: orderNo={}, carrier={}", orderNo, shipping.getCarrier());
		}

		return ShipmentResponse.of(group, shipping, first);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.trim();
	}

	/**
	 * 발송 완료 알림을 적재한다 (알림톡 3번).
	 *
	 * 승인된 템플릿이 아직 없어 지금은 릴레이가 로그만 남기고 넘어간다 (D-040) —
	 * 템플릿 id 를 설정에 채우는 순간 나가기 시작한다. 코드는 손대지 않는다.
	 *
	 * 송장번호를 payload 에 싣는다. 알림이 재시도로 며칠 뒤 나가도 <b>그때 등록한 번호</b>
	 * 여야 하고, 조회로 채우면 그 사이 수정된 번호가 실린다.
	 */
	private void notifyShipped(OrderGroup group, Shipping shipping) {
		outboxRecorder.record(OutboxAggregate.ORDER_GROUP, group.getId(),
				OutboxEventType.SHIPPED,
				Map.of(
						"orderToken", group.getOrderToken(),
						"buyerId", group.getBuyer().getId(),
						"carrier", shipping.getCarrier(),
						"trackingNo", shipping.getTrackingNo()));
	}

	/**
	 * 없는 주문과 남의 주문을 똑같이 404 로 돌려준다 — 403 이면 "그 번호가 있긴 하다" 가
	 * 새어 나가 주문번호를 훑을 수 있다 ({@code SellerOrderService#detail} 과 같은 이유다).
	 */
	private OrderGroup ownedGroup(Seller seller, String orderNo) {
		OrderGroup group = orderGroupRepository.findByOrderNo(orderNo)
				.orElseThrow(() -> new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND));

		if (!group.getSeller().getId().equals(seller.getId())) {
			throw new BusinessException(ErrorCode.ORDER_GROUP_NOT_FOUND);
		}
		return group;
	}
}
