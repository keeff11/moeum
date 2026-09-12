package store.moeum.moeum.order.dto;

/**
 * 배송조회에 필요한 값만 담은 조회 결과.
 *
 * <b>엔티티를 올리지 않는 이유가 트랜잭션 경계다</b> (CLAUDE.md 규칙 1). 배송조회는
 * 외부 호출이라 트랜잭션 밖에서 해야 하는데, 엔티티를 들고 나가면 지연 로딩이 터진다.
 * 필요한 세 값을 한 쿼리로 꺼내면 트랜잭션을 열어 둘 이유가 없다.
 *
 * @param carrier     화면에 보여 줄 택배사 이름
 * @param carrierCode 스마트택배 코드. 없으면 조회를 걸 수 없다
 * @param trackingNo  송장번호
 */
public record ShipmentRef(String carrier, String carrierCode, String trackingNo) {

	public boolean trackable() {
		return carrierCode != null && !carrierCode.isBlank()
				&& trackingNo != null && !trackingNo.isBlank();
	}
}
