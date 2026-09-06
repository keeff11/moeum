package store.moeum.moeum.saleform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import store.moeum.moeum.global.error.BusinessException;
import store.moeum.moeum.global.error.ErrorCode;
import store.moeum.moeum.saleform.dto.ImageUploadUrlRequest;
import store.moeum.moeum.saleform.dto.ImageUploadUrlResponse;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이미지 업로드 URL 발급 (D-022).
 *
 * 서버가 파일 바이트를 안 보므로 검증할 지점이 발급 시점밖에 없다.
 * 그래서 여기서 보는 것은 "무엇을 서명에 넣었는가" 다 —
 * 키를 서버가 만들었는지, 형식과 크기를 걸렀는지.
 *
 * 서명은 전부 로컬 계산이라 실제 AWS 없이 검증된다.
 */
class ImageUploadUrlTest extends IntegrationTest {

	private static final String APPROVED = "kakao-upload-approved";
	private static final String PENDING = "kakao-upload-pending";

	@Autowired
	private SaleFormService saleFormService;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private OrderFixture fixture;

	private Long approvedSellerId;

	@BeforeEach
	void setUp() {
		fixture.clean();

		Seller approved = sellerRepository.save(Seller.builder()
				.kakaoId(APPROVED).storeSlug("upload-store").storeName("업로드 상점").shippingFee(3000).build());
		approved.approve();
		sellerRepository.saveAndFlush(approved);
		this.approvedSellerId = approved.getId();

		// 심사 대기 상태 그대로 둔다
		sellerRepository.save(Seller.builder()
				.kakaoId(PENDING).storeSlug("pending-store").storeName("대기 상점").shippingFee(3000).build());
	}

	@Test
	@DisplayName("키는_서버가_만들고_셀러_id_로_경로가_나뉜다")
	void 키는_서버가_만든다() {
		ImageUploadUrlResponse response = issue("image/jpeg", 1024);

		// 클라이언트가 키를 정하게 두면 남의 경로를 지정해 덮어쓸 수 있다
		assertThat(response.objectKey()).startsWith("sale-forms/" + approvedSellerId + "/");
		assertThat(response.objectKey()).endsWith(".jpg");
		assertThat(response.uploadUrl()).contains("moeum-test-bucket");
		assertThat(response.contentType()).isEqualTo("image/jpeg");
		assertThat(response.expiresInSeconds()).isEqualTo(300);
	}

	@Test
	@DisplayName("두_번_발급하면_키가_겹치지_않는다")
	void 키가_겹치지_않는다() {
		assertThat(issue("image/png", 2048).objectKey())
				.isNotEqualTo(issue("image/png", 2048).objectKey());
	}

	@Test
	@DisplayName("발급된_URL_은_만료와_서명을_달고_나간다")
	void URL_에_만료와_서명이_있다() {
		String url = issue("image/webp", 4096).uploadUrl();

		// 새어 나가도 짧은 시간만 쓸 수 있어야 한다
		assertThat(url).contains("X-Amz-Expires=300");
		assertThat(url).contains("X-Amz-Signature=");
		// Content-Length 를 서명에 넣어야 크기 상한이 실제로 강제된다
		assertThat(url).contains("content-length");
	}

	@Test
	@DisplayName("확장자는_요청한_형식을_따른다")
	void 확장자가_형식을_따른다() {
		assertThat(issue("image/png", 100).objectKey()).endsWith(".png");
		assertThat(issue("image/webp", 100).objectKey()).endsWith(".webp");
		assertThat(issue("image/gif", 100).objectKey()).endsWith(".gif");
		// 대소문자·공백이 섞여 와도 같은 것으로 본다
		assertThat(issue("  IMAGE/JPEG ", 100).objectKey()).endsWith(".jpg");
	}

	@Test
	@DisplayName("이미지가_아닌_형식은_발급하지_않는다")
	void 이미지가_아니면_거부한다() {
		// 서버가 바이트를 못 보므로 여기서 막지 않으면 무엇이든 버킷에 들어간다
		assertThatThrownBy(() -> issue("application/pdf", 1024))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE);

		assertThatThrownBy(() -> issue("text/html", 1024))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("상한을_넘는_크기는_발급하지_않는다")
	void 너무_크면_거부한다() {
		assertThatThrownBy(() -> issue("image/jpeg", 10L * 1024 * 1024 + 1))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.IMAGE_TOO_LARGE);
	}

	@Test
	@DisplayName("미승인_셀러는_발급받지_못한다")
	void 미승인_셀러는_거부한다() {
		assertThatThrownBy(() -> saleFormService.issueImageUploadUrl(
				PENDING, new ImageUploadUrlRequest("image/jpeg", 1024)))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).errorCode())
				.isEqualTo(ErrorCode.SELLER_NOT_APPROVED);
	}

	private ImageUploadUrlResponse issue(String contentType, long contentLength) {
		return saleFormService.issueImageUploadUrl(
				APPROVED, new ImageUploadUrlRequest(contentType, contentLength));
	}
}
