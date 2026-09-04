package store.moeum.moeum.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 민감정보 컬럼(VARBINARY) 암·복호화. AES-256-GCM.
 *
 * 저장 형식: [12바이트 IV][암호문 + 16바이트 인증 태그]
 * IV 는 매번 새로 뽑는다. 같은 평문이 같은 암호문이 되지 않으므로 이 컬럼으로는 동등 검색을 할 수 없다.
 * 사업자번호·정산계좌를 조건으로 조회할 일이 없어서 검색 가능성보다 안전한 쪽을 택했다.
 *
 * 예외 메시지에 평문을 절대 담지 않는다.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final int KEY_BYTES = 32;

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public EncryptedStringConverter(@Value("${moeum.crypto.seller-key}") String base64Key) {
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(base64Key);
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("moeum.crypto.seller-key 가 Base64 가 아니다");
		}
		if (raw.length != KEY_BYTES) {
			throw new IllegalStateException(
					"moeum.crypto.seller-key 는 32바이트(AES-256) 여야 한다. 현재 " + raw.length + "바이트");
		}
		this.key = new SecretKeySpec(raw, "AES");
	}

	@Override
	public byte[] convertToDatabaseColumn(String attribute) {
		if (attribute == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

			return ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText).array();
		} catch (Exception e) {
			// 평문이 섞이지 않도록 원인 예외의 메시지도 붙이지 않는다
			throw new IllegalStateException("민감정보 암호화에 실패했다: " + e.getClass().getSimpleName());
		}
	}

	@Override
	public String convertToEntityAttribute(byte[] dbData) {
		if (dbData == null) {
			return null;
		}
		if (dbData.length <= IV_BYTES) {
			throw new IllegalStateException("암호문 길이가 올바르지 않다: " + dbData.length + "바이트");
		}
		try {
			ByteBuffer buffer = ByteBuffer.wrap(dbData);
			byte[] iv = new byte[IV_BYTES];
			buffer.get(iv);
			byte[] cipherText = new byte[buffer.remaining()];
			buffer.get(cipherText);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

			return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("민감정보 복호화에 실패했다: " + e.getClass().getSimpleName());
		}
	}
}
