package store.moeum.moeum.buyer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import store.moeum.moeum.buyer.domain.BuyerAddress;

import java.time.LocalDateTime;

public record AddressResponse(
		@Schema(description = "받는 사람 이름", example = "홍길동")
		String recipientName,

		@Schema(description = "받는 사람 휴대폰 번호", example = "010-1234-5678")
		String phone,

		@Schema(description = "우편번호", example = "06234")
		String postalCode,

		@Schema(description = "기본 주소", example = "서울 강남구 테헤란로 1")
		String address1,

		@Schema(description = "상세 주소", example = "101동 1001호")
		String address2,

		@Schema(description = "배송 메모")
		String memo,

		@Schema(description = "마지막으로 수정한 시각")
		LocalDateTime updatedAt
) {

	public static AddressResponse from(BuyerAddress address) {
		return new AddressResponse(
				address.getRecipientName(),
				address.getPhone(),
				address.getPostalCode(),
				address.getAddress1(),
				address.getAddress2(),
				address.getMemo(),
				address.getUpdatedAt()
		);
	}
}
