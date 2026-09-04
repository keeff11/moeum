package store.moeum.moeum.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManager;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.ReviewStatus;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.support.IntegrationTest;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매핑이 docs/schema.sql 과 어긋나지 않는지 확인한다.
 *
 * ddl-auto=validate 는 "엔티티가 요구하는 컬럼이 DB 에 있는가" 한 방향만 본다.
 * 반대 방향(DB 에는 있는데 매핑을 빠뜨린 컬럼)은 잡아 주지 않으므로 여기서 양방향으로 대조한다.
 */
class EntityMappingTest extends IntegrationTest {

	@Autowired
	private EntityManager em;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("네_테이블의_컬럼이_엔티티와_정확히_일치한다")
	void 네_테이블의_컬럼이_엔티티와_정확히_일치한다() {
		assertColumnsMatch(Seller.class, "seller");
		assertColumnsMatch(SaleForm.class, "sale_form");
		assertColumnsMatch(Product.class, "product");
		assertColumnsMatch(ProductOption.class, "product_option");
	}

	@Test
	@Transactional
	@DisplayName("연관관계는_전부_LAZY다")
	void 연관관계는_전부_LAZY다() {
		Seller seller = persistFixture();
		em.flush();
		em.clear();

		PersistenceUnitUtil util = em.getEntityManagerFactory().getPersistenceUnitUtil();

		SaleForm form = em.createQuery("select f from SaleForm f where f.seller.id = :id", SaleForm.class)
				.setParameter("id", seller.getId())
				.getSingleResult();

		assertThat(util.isLoaded(form, "seller")).isFalse();
		assertThat(util.isLoaded(form, "products")).isFalse();

		Product product = form.getProducts().get(0);
		assertThat(util.isLoaded(product, "options")).isFalse();
	}

	@Test
	@Transactional
	@DisplayName("민감정보는_DB에_암호문으로_들어가고_읽을_때_복호화된다")
	void 민감정보는_DB에_암호문으로_들어가고_읽을_때_복호화된다() {
		Seller seller = persistFixture();
		em.flush();
		em.clear();

		byte[] stored = jdbcTemplate.queryForObject(
				"SELECT business_no_enc FROM seller WHERE id = ?", byte[].class, seller.getId());

		assertThat(stored).isNotNull();
		assertThat(new String(stored)).doesNotContain("1234567890");
		assertThat(stored.length).isGreaterThan(12 + 16);

		Seller reloaded = em.find(Seller.class, seller.getId());
		assertThat(reloaded.getBusinessNo()).isEqualTo("1234567890");
		assertThat(reloaded.getSettlementAccount()).isEqualTo("국민 123456-78-901234");
	}

	@Test
	@Transactional
	@DisplayName("같은_평문이라도_매번_다른_암호문이_된다")
	void 같은_평문이라도_매번_다른_암호문이_된다() {
		Seller a = Seller.builder().kakaoId("k-a").storeSlug("s-a").businessNo("1234567890").build();
		Seller b = Seller.builder().kakaoId("k-b").storeSlug("s-b").businessNo("1234567890").build();
		em.persist(a);
		em.persist(b);
		em.flush();

		byte[] encA = jdbcTemplate.queryForObject(
				"SELECT business_no_enc FROM seller WHERE id = ?", byte[].class, a.getId());
		byte[] encB = jdbcTemplate.queryForObject(
				"SELECT business_no_enc FROM seller WHERE id = ?", byte[].class, b.getId());

		assertThat(encA).isNotEqualTo(encB);
	}

	@Test
	@Transactional
	@DisplayName("기본값과_감사시각이_채워진다")
	void 기본값과_감사시각이_채워진다() {
		Seller seller = persistFixture();
		em.flush();
		em.clear();

		Seller reloaded = em.find(Seller.class, seller.getId());
		assertThat(reloaded.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
		assertThat(reloaded.getCreatedAt()).isNotNull();
		assertThat(reloaded.getUpdatedAt()).isNotNull();

		SaleForm form = em.createQuery("select f from SaleForm f where f.seller.id = :id", SaleForm.class)
				.setParameter("id", seller.getId())
				.getSingleResult();
		assertThat(form.getStatus()).isEqualTo(SaleFormStatus.DRAFT);
		assertThat(form.getMaxPerUser()).isEqualTo(2);
		assertThat(form.getMinOrderAmount()).isEqualTo(10000);
		assertThat(form.isProgressPublic()).isTrue();
		assertThat(form.getHeld()).isZero();
		assertThat(form.getSold()).isZero();
		assertThat(form.getProducts()).hasSize(1);
		assertThat(form.getProducts().get(0).getOptions()).hasSize(2);
	}

	/**
	 * held/sold 는 DB 가 주인이다. 엔티티의 다른 필드를 고쳐 flush 해도
	 * 메모리에 있던 낡은 값이 네이티브 UPDATE 결과를 덮으면 안 된다.
	 */
	@Test
	@Transactional
	@DisplayName("다른_필드를_수정해_flush해도_held와_sold를_덮어쓰지_않는다")
	void 다른_필드를_수정해_flush해도_held와_sold를_덮어쓰지_않는다() {
		Seller seller = persistFixture();
		em.flush();

		Long formId = em.createQuery("select f.id from SaleForm f where f.seller.id = :id", Long.class)
				.setParameter("id", seller.getId())
				.getSingleResult();
		em.clear();

		// held/sold 가 0 인 상태로 엔티티를 로딩한다
		SaleForm loaded = em.find(SaleForm.class, formId);
		assertThat(loaded.getHeld()).isZero();

		// 그 사이 3단계의 조건부 UPDATE 가 값을 바꾼다
		jdbcTemplate.update("UPDATE sale_form SET held = 7, sold = 3 WHERE id = ?", formId);

		// 낡은 엔티티의 다른 필드를 고쳐 flush
		loaded.addProduct(Product.builder().name("추가 상품").sortOrder(1).build());
		em.flush();
		em.clear();

		Integer held = jdbcTemplate.queryForObject(
				"SELECT held FROM sale_form WHERE id = ?", Integer.class, formId);
		Integer sold = jdbcTemplate.queryForObject(
				"SELECT sold FROM sale_form WHERE id = ?", Integer.class, formId);

		assertThat(held).isEqualTo(7);
		assertThat(sold).isEqualTo(3);
	}

	private Seller persistFixture() {
		long unique = System.nanoTime();
		Seller seller = Seller.builder()
				.kakaoId("kakao-" + unique)
				.storeSlug("slug-" + unique)
				.businessNo("1234567890")
				.settlementAccount("국민 123456-78-901234")
				.representativeName("홍길동")
				.phone("010-0000-0000")
				.email("seller@example.com")
				.build();
		em.persist(seller);

		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("겨울 공동구매")
				.slug("winter-" + unique)
				.saleType(SaleType.GROUP)
				.stockMax(100)
				.targetQty(30)
				.maxPerUser(2)
				.shipStartText("8월 20일(월) 순차발송")
				.minOrderAmount(10000)
				.build();

		Product product = Product.builder().name("머플러").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("네이비").deposit1Amount(19000).deposit2Amount(0).sortOrder(0).build());
		product.addOption(ProductOption.builder()
				.name("버건디").deposit1Amount(10000).deposit2Amount(9500).sortOrder(1).build());
		form.addProduct(product);

		em.persist(form);
		return seller;
	}

	private void assertColumnsMatch(Class<?> entityClass, String tableName) {
		Set<String> dbColumns = Set.copyOf(jdbcTemplate.queryForList(
				"SELECT LOWER(column_name) FROM information_schema.columns "
						+ "WHERE table_schema = DATABASE() AND table_name = ?",
				String.class, tableName));

		assertThat(mappedColumns(entityClass))
				.as("%s <-> %s 컬럼 대조", entityClass.getSimpleName(), tableName)
				.containsExactlyInAnyOrderElementsOf(dbColumns);
	}

	/** 엔티티(와 상위 클래스)의 필드에서 실제로 매핑한 컬럼명을 모은다 */
	private Set<String> mappedColumns(Class<?> entityClass) {
		Set<String> columns = new HashSet<>();
		for (Class<?> type = entityClass; type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())
						|| field.isAnnotationPresent(Transient.class)
						|| field.isAnnotationPresent(OneToMany.class)) {
					continue;
				}
				JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
				if (joinColumn != null) {
					columns.add(joinColumn.name().toLowerCase());
					continue;
				}
				Column column = field.getAnnotation(Column.class);
				if (column != null) {
					columns.add(column.name().toLowerCase());
				}
			}
		}
		return columns;
	}
}
