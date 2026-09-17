package store.moeum.moeum.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.buyer.domain.Buyer;
import store.moeum.moeum.buyer.domain.BuyerAddressRepository;
import store.moeum.moeum.order.domain.OrderGroup;
import store.moeum.moeum.order.domain.OrderGroupRepository;
import store.moeum.moeum.order.domain.Shipping;
import store.moeum.moeum.order.domain.ShippingRepository;
import store.moeum.moeum.outbox.domain.OutboxAggregate;
import store.moeum.moeum.outbox.domain.OutboxEventType;
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
		OutboxEventType eventType = message.eventType();

		if (properties.templateOf(eventType) == null && properties.soloTemplateOf(eventType) == null) {
			return Optional.empty();
		}
		if (message.aggregateType() != OutboxAggregate.ORDER_GROUP) {
			// 지금 적재되는 것은 전부 주문 묶음이다. 다른 것이 생기면 여기서 갈린다
			return Optional.empty();
		}

		OrderGroup group = orderGroupRepository.findById(message.aggregateId())
				.orElseThrow(() -> new SolapiFailedException(
						"알림 대상 주문을 찾을 수 없다: outboxId=" + message.id()));

		boolean solo = !group.hasSecondPayment() && properties.soloTemplateOf(eventType) != null;
		String templateId = solo ? properties.soloTemplateOf(eventType) : properties.templateOf(eventType);

		if (templateId == null) {
			// 단독 판매 템플릿만 있고 이 묶음은 2차금이 있다. 보낼 문구가 없다
			return Optional.empty();
		}

		Optional<Map<String, String>> variables = variablesOf(group, message, solo);

		if (variables.isEmpty()) {
			// 템플릿 id 는 있는데 채울 변수를 모른다. 그대로 보내면 4xx 로 거절돼 DEAD 로 쌓인다
			log.warn("[알림/변수미정] 템플릿은 있지만 변수를 채우는 코드가 없다: {} outboxId={}",
					eventType, message.id());
			return Optional.empty();
		}

		return Optional.of(new SolapiSendRequest.Message(
				recipientOf(group, message),
				properties.from(),
				new SolapiSendRequest.KakaoOption(properties.pfId(), templateId, variables.get())));
	}

	// ---------------------------------------------------------------- 내부

	/**
	 * 수신번호.
	 *
	 * <b>테스트 수신번호가 설정돼 있으면 그쪽으로만 간다</b> — 구매자에게는 한 통도
	 * 가지 않는다. 수신번호 정책이 정해지기 전까지 알림톡을 켜 둘 수 있는 유일한 방법이다.
	 *
	 * 그다음은 <b>구매자가 문자로 인증한 번호</b>다 (D-064). 구매자 본인 번호라
	 * 선물 주문이어도 구매자에게 간다.
	 *
	 * <b>⚠ 인증 전이면 배송지 번호를 쓴다.</b> 선물 주문처럼 수령인이 구매자와 다르면
	 * 결제 알림이 받는 사람에게 간다 (D-040). 인증을 마치지 않은 구매자에게 남는 제약이다.
	 * 주문 시점 스냅샷을 먼저 보고, 없으면 현재 배송지에서 가져온다.
	 *
	 * <b>인증 번호는 지금 값을 본다 — 스냅샷을 뜨지 않는다.</b> 번호를 바꿨다면 새 번호가
	 * 맞는 번호다. 배송지와 달리 "그때 어디로 보냈는가" 를 굳혀 둘 이유가 없다.
	 */
	private String recipientOf(OrderGroup group, OutboxMessage message) {
		String testRecipient = properties.testRecipientOrNull();

		if (testRecipient != null) {
			// 실서비스로 넘어갈 때 지우는 것을 잊으면 모든 알림이 한 사람에게만 간다.
			// 잊기 쉬운 자리라 나갈 때마다 남긴다
			log.warn("[알림/테스트수신] 구매자가 아니라 테스트 번호로 보낸다: outboxId={}", message.id());
			return testRecipient;
		}

		Buyer buyer = group.getBuyer();

		if (buyer.hasNotifyPhone()) {
			return buyer.getNotifyPhone();
		}

		String phone = shippingRepository.findByOrderGroupId(group.getId())
				.map(Shipping::getPhone)
				.filter(value -> value != null && !value.isBlank())
				.orElseGet(() -> buyerAddressRepository.findByBuyerId(buyer.getId())
						.map(address -> address.getPhone())
						.orElse(null));

		if (phone == null || phone.isBlank()) {
			throw new SolapiFailedException("수신번호가 없다: outboxId=" + message.id());
		}
		return digitsOf(phone);
	}

	/**
	 * 템플릿 변수. <b>이름이 승인본과 하나라도 다르면 SOLAPI 가 거절한다.</b>
	 *
	 * 승인된 템플릿마다 변수가 다르다 (2026-09-17 콘솔에서 확인).
	 * <ul>
	 *   <li>1차금 결제 완료 — userName · goodsName · prepayment · billTime · LINK</li>
	 *   <li>단독 판매 결제 완료 — userName · goodsName · <b>payment</b> · billTime · LINK</li>
	 *   <li>2차금 청구(주문 상태 변경 안내) — userName · goodsName · orderNo · LINK</li>
	 *   <li>발송 완료 — userName · goodsName · deliveryCompany · trackingNumber · LINK</li>
	 * </ul>
	 * 모르는 이벤트는 비어 있음을 돌려준다 — 템플릿 id 만 넣고 여기를 안 고치면 로그로 드러난다.
	 *
	 * <b>키는 {@code #{}} 를 붙인 그대로 보낸다.</b> SOLAPI 가 알아서 감싸 주기도 하지만
	 * 그 규칙에 기대면 템플릿에 {@code #{}} 가 없는 변수가 섞였을 때 조용히 어긋난다.
	 */
	private Optional<Map<String, String>> variablesOf(OrderGroup group, OutboxMessage message,
	                                                  boolean solo) {
		Map<String, String> variables = new LinkedHashMap<>();

		variables.put("#{userName}", userNameOf(group));
		variables.put("#{goodsName}", group.representativeTitle());

		switch (message.eventType()) {
			case ORDER_PAID -> {
				JsonNode payload = payloadOf(message);
				variables.put(solo ? "#{payment}" : "#{prepayment}", wonOf(payload.path("amount").asInt()));
				variables.put("#{billTime}", message.createdAt().format(BILL_TIME));
			}
			case SECOND_PAYMENT_DUE -> variables.put("#{orderNo}", group.getOrderNo());
			case SHIPPED -> {
				// 송장은 적재할 때 실은 값을 쓴다. 조회하면 그 사이 고친 번호가 실린다 (ShipmentService)
				JsonNode payload = payloadOf(message);
				variables.put("#{deliveryCompany}", payload.path("carrier").asText());
				variables.put("#{trackingNumber}", payload.path("trackingNo").asText());
			}
			default -> {
				return Optional.empty();
			}
		}

		variables.put("#{LINK}", linkOf(group));
		return Optional.of(variables);
	}

	/**
	 * 호칭은 수신번호의 주인에 맞춘다.
	 *
	 * 인증 번호로 가면 구매자 본인이 받으므로 카카오 닉네임을 쓴다 — 선물 주문에서
	 * 수령인 이름을 쓰면 구매자가 받는 사람 이름으로 불린다. 배송지 번호로 가면 받는 사람이
	 * 수령인이라 배송지에 적힌 이름을 먼저 쓴다.
	 */
	private String userNameOf(OrderGroup group) {
		Buyer buyer = group.getBuyer();

		if (properties.testRecipientOrNull() == null && buyer.hasNotifyPhone()
				&& buyer.getNickname() != null && !buyer.getNickname().isBlank()) {
			return buyer.getNickname();
		}
		return shippingRepository.findByOrderGroupId(group.getId())
				.map(Shipping::getRecipientName)
				.filter(name -> name != null && !name.isBlank())
				.orElseGet(buyer::getNickname);
	}

	/**
	 * payload. 금액은 여기의 {@code amount} 가 실제로 청구된 값이다.
	 *
	 * 묶음에서 다시 계산하지 않는다 — 부분 취소가 있으면 지금 계산한 값과
	 * 그때 결제한 값이 다르다. 알림은 그때 일어난 사실을 말해야 한다.
	 */
	private static JsonNode payloadOf(OutboxMessage message) {
		try {
			return MAPPER.readTree(message.payload());

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
