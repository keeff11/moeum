package store.moeum.moeum.global.flyway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import store.moeum.moeum.support.IntegrationTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1단계 확인 조건: 앱이 뜨고 테이블이 전부 생성되어 있다.
 */
class FlywayMigrationTest extends IntegrationTest {

	/** docs/schema.sql (v3) 의 테이블 18개 */
	private static final List<String> EXPECTED_TABLES = List.of(
			"seller", "sale_form", "sale_form_history", "product", "product_option",
			"buyer", "buyer_address", "cart", "cart_item", "order_group", "orders",
			"order_item", "stock_hold", "payment", "payment_event", "refund",
			"shipping", "outbox"
	);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("V1_마이그레이션이_성공으로_기록된다")
	void V1_마이그레이션이_성공으로_기록된다() {
		Boolean success = jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);

		assertThat(success).isTrue();
	}

	@Test
	@DisplayName("스키마의_테이블이_전부_생성된다")
	void 스키마의_테이블이_전부_생성된다() {
		Set<String> actual = Set.copyOf(jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
				String.class));

		assertThat(actual).containsAll(EXPECTED_TABLES);
		assertThat(actual).hasSize(EXPECTED_TABLES.size() + 1); // + flyway_schema_history
	}

	@Test
	@DisplayName("금액_컬럼은_정수_타입이다")
	void 금액_컬럼은_정수_타입이다() {
		List<String> floatingPointMoneyColumns = jdbcTemplate.queryForList("""
				SELECT CONCAT(table_name, '.', column_name)
				  FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				   AND data_type IN ('float', 'double', 'decimal')
				""", String.class);

		assertThat(floatingPointMoneyColumns).isEmpty();
	}
}
