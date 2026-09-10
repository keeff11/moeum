package store.moeum.moeum.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.infra.SolapiFailedException;
import store.moeum.moeum.outbox.infra.SolapiProperties;
import store.moeum.moeum.outbox.infra.SolapiSendRequest;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * outbox 한 줄을 알림톡 한 통으로 바꾼다.
 *
 * <b>발송기와 나눠 둔 이유는 트랜잭션 경계다.</b> 여기는 DB 를 읽고(짧은 트랜잭션),
 * 발송기는 외부를 부른다(트랜잭션 밖, CLAUDE.md 규칙 1). 한 클래스에 두면
 * 외부 호출이 트랜잭션 안으로 들어온다.
 *
 * <b>payload 를 넓히지 않고 조회로 채운다.</b> 적재 지점이 넷이라(결제·입고·2차금·환불)
 * 템플릿이 늘 때마다 넷을 다 고치게 되고, 이미 쌓인 PENDING 행은 옛 payload 라
 * 새 변수를 못 채운다. 조회로 채우면 적재 쪽은 그대로 두고 여기만 바뀐다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlimtalkMessageFactory {

	/** 결제일시 문구. 초까지 쓰지 않는다 — 사람이 읽는 값이다 */
	private static final DateTimeFormatter BILL_TIME =
			DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final OrderGroupRepository orderGroupRepository;
	private final ShippingRepository shippingRepository;
	private final BuyerAddressRepository buyerAddressRepository;
	private final SolapiProperties properties;

	/**
	 * 보낼 수 있으면 한 통을 만들고, 보낼 수 없으면 비어 있음을 돌려준다.
	 *
	 * <b>비어 있음은 실패가 아니다.</b> 승인된 템플릿이 없는 이벤트가 그렇다 —
	 * 예외로 올리면 릴레이가 8회 재시도한 뒤 DEAD 로 쌓는다. 템플릿이 없다는 것은
	 * 다시 시도해서 풀릴 일이 아니라 기다려야 하는 일이다.
	 */
	@Transactional(readOnly = true)
	public Optional<SolapiSendRequest.Message> create(OutboxMessage message) {
		String templateId = properties.templateOf(message.eventType());

		if (templateId == null) {
			return Optional.empty();
		}
		if (message.aggregateType() != OutboxAggregate.ORDER_GROUP) {
			// 지금 적재되는 것은 전부 주문 묶음이다. 다른 것이 생기면 여기서 갈린다
			return Optional.empty();
		}

		OrderGroup group = orderGroupRepository.findById(message.aggregateId())
				.orElseThrow(() -> new SolapiFailedException(
						"알림 대상 주문을 찾을 수 없다: outboxId=" + message.id()));

		return Optional.of(new SolapiSendRequest.Message(
				recipientOf(group, message),
				properties.from(),
				new SolapiSendRequest.KakaoOption(properties.pfId(), templateId,
						variablesOf(group, message))));
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 수신번호.
	 *
	 * <b>⚠ 지금은 배송지 번호를 쓴다 — 구매자 본인 번호가 DB 에 없다.</b>
	 * 선물 주문처럼 수령인이 구매자와 다르면 결제 알림이 받는 사람에게 간다 (D-040).
	 * 카카오 로그인 동의항목에 전화번호를 추가하기 전까지 남는 제약이다.
	 * 주문 시점 스냅샷을 먼저 보고, 없으면 현재 배송지에서 가져온다.
	 */
	private String recipientOf(OrderGroup group, OutboxMessage message) {
		String phone = shippingRepository.findByOrderGroupId(group.getId())
				.map(Shipping::getPhone)
				.filter(value -> value != null && !value.isBlank())
				.orElseGet(() -> buyerAddressRepository.findByBuyerId(group.getBuyer().getId())
						.map(address -> address.getPhone())
						.orElse(null));

		if (phone == null || phone.isBlank()) {
			throw new SolapiFailedException("수신번호가 없다: outboxId=" + message.id());
		}
		return digitsOf(phone);
	}

	/**
	 * 템플릿 변수.
	 *
	 * 승인된 것은 결제완료 하나뿐이라 그 변수만 채운다. 템플릿이 늘면 이벤트별로
	 * 갈라야 하는데, 지금 갈라 두면 쓰지 않는 분기가 생긴다.
	 *
	 * <b>키는 {@code #{}} 를 붙인 그대로 보낸다.</b> SOLAPI 가 알아서 감싸 주기도 하지만
	 * 그 규칙에 기대면 템플릿에 {@code #{}} 가 없는 변수가 섞였을 때 조용히 어긋난다.
	 */
	private Map<String, String> variablesOf(OrderGroup group, OutboxMessage message) {
		Map<String, String> variables = new LinkedHashMap<>();

		variables.put("#{userName}", userNameOf(group));
		variables.put("#{goodsName}", group.representativeTitle());
		variables.put("#{prepayment}", wonOf(amountOf(message)));
		variables.put("#{billTime}", message.createdAt().format(BILL_TIME));
		variables.put("#{LINK}", linkOf(group));

		return variables;
	}

	/** 배송지에 적힌 이름을 먼저 쓴다 — 수신번호가 그쪽 번호라 호칭도 맞춰야 한다 */
	private String userNameOf(OrderGroup group) {
		return shippingRepository.findByOrderGroupId(group.getId())
				.map(Shipping::getRecipientName)
				.filter(name -> name != null && !name.isBlank())
				.orElseGet(() -> group.getBuyer().getNickname());
	}

	/**
	 * 금액. payload 의 {@code amount} 가 실제로 청구된 값이다.
	 *
	 * 묶음에서 다시 계산하지 않는다 — 부분 취소가 있으면 지금 계산한 값과
	 * 그때 결제한 값이 다르다. 알림은 그때 일어난 사실을 말해야 한다.
	 */
	private static int amountOf(OutboxMessage message) {
		try {
			JsonNode node = MAPPER.readTree(message.payload()).get("amount");
			return (node == null) ? 0 : node.asInt();

		} catch (Exception e) {
			throw new SolapiFailedException("알림 payload 를 읽을 수 없다: outboxId=" + message.id(), e);
		}
	}

	/**
	 * 자릿수를 끊는다 — 템플릿이 "결제 금액 : #{prepayment}원" 이라 20,000원 이 된다.
	 *
	 * {@code DecimalFormat} 을 상수로 두지 않는다. <b>스레드 안전하지 않다</b> —
	 * 지금은 릴레이가 한 스레드지만, 나중에 병렬로 돌리는 순간 금액이 섞인다.
	 */
	private static String wonOf(int amount) {
		return String.format("%,d", amount);
	}

	/** 버튼 링크. 템플릿의 모바일·PC 웹링크가 같은 {@code #{LINK}} 를 쓴다 */
	private String linkOf(OrderGroup group) {
		return properties.linkBase() + "/orders/" + group.getOrderToken();
	}

	/** 하이픈이 섞인 번호가 그대로 들어오면 접수되지 않는다 */
	private static String digitsOf(String phone) {
		return phone.replaceAll("[^0-9]", "");
	}
}
