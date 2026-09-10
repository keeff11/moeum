package store.moeum.moeum.global.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;
import store.moeum.moeum.support.IntegrationTest;
import store.moeum.moeum.support.OrderFixture;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 참조 이미지 키 수집 (D-041).
 *
 * <b>고아 청소의 안전이 전부 여기에 달려 있다.</b> 청소 배치는 이 목록 밖의 객체를 지우므로,
 * 참조처가 한 건이라도 빠지면 그게 곧 운영 이미지 삭제다.
 * 그래서 "실제로 DB 에 있는 키가 전부 나오는가" 를 본다.
 */
class ImageKeySourceTest extends IntegrationTest {

	@Autowired
	private List<ImageKeySource> keySources;

	@Autowired
	private SaleFormRepository saleFormRepository;

	@Autowired
	private SellerRepository sellerRepository;

	@Autowired
	private OrderFixture fixture;

	/**
	 * 이미지 컬렉션이 LAZY 라 트랜잭션 밖에서는 못 건드린다.
	 * 서비스가 하는 것과 같은 경로로 넣어야 실제로 남는 행을 보게 된다.
	 */
	@Autowired
	private TransactionTemplate transactionTemplate;

	@BeforeEach
	void setUp() {
		fixture.clean();
	}

	@Test
	@DisplayName("폼_이미지와_프로필_사진이_모두_참조로_잡힌다")
	void 모든_참조처가_수집된다() {
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		replaceImages(setup.saleFormId(), List.of("sale-forms/1/thumb.jpg", "sale-forms/1/detail.jpg"));

		Seller seller = sellerRepository.findById(setup.sellerId()).orElseThrow();
		seller.updateProfile("상점", null, null, "sale-forms/1/profile.jpg", null);
		sellerRepository.saveAndFlush(seller);

		assertThat(collect()).containsExactlyInAnyOrder(
				"sale-forms/1/thumb.jpg", "sale-forms/1/detail.jpg", "sale-forms/1/profile.jpg");
	}

	@Test
	@DisplayName("폼에서_뺀_이미지는_참조에서_빠진다")
	void 교체된_이미지는_참조가_아니다() {
		// 이 경로가 고아의 두 번째 출처다 — replaceImages 는 DB 행만 갈아끼우고 S3 는 그대로 둔다
		OrderFixture.Setup setup = fixture.saleForm(10, null);

		replaceImages(setup.saleFormId(), List.of("sale-forms/1/old.jpg"));
		replaceImages(setup.saleFormId(), List.of("sale-forms/1/new.jpg"));

		assertThat(collect()).contains("sale-forms/1/new.jpg");
		assertThat(collect()).doesNotContain("sale-forms/1/old.jpg");
	}

	@Test
	@DisplayName("프로필_사진이_없는_셀러는_빈_값을_내지_않는다")
	void 빈_키는_섞이지_않는다() {
		// null 이나 빈 문자열이 참조 집합에 들어가면 판정에 아무 영향이 없지만, 건수 로그가 어긋난다
		fixture.saleForm(10, null);

		assertThat(collect()).doesNotContain("", null);
	}

	@Test
	@DisplayName("참조처_구현체가_빠짐없이_등록되어_있다")
	void 참조처가_모두_등록되어_있다() {
		// 이미지 키를 저장하는 곳이 늘면 이 목록도 같이 늘어야 한다. 안 늘면 그 이미지가 지워진다
		assertThat(keySources).extracting(ImageKeySource::sourceName)
				.containsExactlyInAnyOrder("sale_form_image", "seller.profile_image_key");
	}

	private void replaceImages(Long saleFormId, List<String> objectKeys) {
		transactionTemplate.executeWithoutResult(status -> {
			SaleForm form = saleFormRepository.findById(saleFormId).orElseThrow();
			form.replaceImages(objectKeys);
			saleFormRepository.saveAndFlush(form);
		});
	}

	private Set<String> collect() {
		return keySources.stream()
				.flatMap(source -> source.referencedImageKeys().stream())
				.collect(java.util.stream.Collectors.toSet());
	}
}
