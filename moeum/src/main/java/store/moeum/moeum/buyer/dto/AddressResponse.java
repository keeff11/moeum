package store.moeum.moeum.buyer.dto;

import store.moeum.moeum.buyer.domain.BuyerAddress;

import java.time.LocalDateTime;

public record AddressResponse(
		String recipientName,
		String phone,
		String postalCode,
		String address1,
		String address2,
		String memo,
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
