package store.moeum.moeum.global;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import store.moeum.moeum.support.IntegrationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 스웨거 문서에 한글 설명이 실제로 올라가는지.
 *
 * <b>주석은 스웨거에 나오지 않는다.</b> springdoc 은 javadoc 을 읽지 않아서,
 * {@code @Schema(description = ...)} 를 붙이지 않으면 프론트에는 필드 이름만 보인다 —
 * {@code q} 나 {@code bio} 처럼 짧은 이름은 그것만으로 뜻을 알 수 없다.
 *
 * 새 DTO 를 만들면서 설명을 빠뜨리면 여기서 걸린다.
 */
@AutoConfigureMockMvc
class OpenApiDocTest extends IntegrationTest {

	/** 설명이 없어도 이상하지 않은 것들 — 값이 하나뿐이거나 이름이 곧 뜻인 응답 */
	private static final List<String> SKIPPED_SCHEMAS = List.of();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private JsonNode doc;

	@BeforeEach
	void setUp() throws Exception {
		String json = mockMvc.perform(get("/v3/api-docs"))
				.andReturn().getResponse().getContentAsString();
		doc = objectMapper.readTree(json);
	}

	// ---------------------------------------------------------------- 필드 설명

	@Test
	@DisplayName("응답_요청_본문의_모든_속성에_설명이_있다")
	void 모든_속성에_설명() {
		List<String> missing = new ArrayList<>();

		doc.path("components").path("schemas").fields().forEachRemaining(schema -> {
			if (SKIPPED_SCHEMAS.contains(schema.getKey())) {
				return;
			}
			schema.getValue().path("properties").fields().forEachRemaining(property -> {
				if (!property.getValue().hasNonNull("description")) {
					missing.add(schema.getKey() + "." + property.getKey());
				}
			});
		});

		// 이름만 보고 뜻을 알 수 없는 필드가 프론트에 그대로 나간다
		assertThat(missing)
				.as("설명이 빠진 속성: %s", missing)
				.isEmpty();
	}

	@Test
	@DisplayName("쿼리_파라미터와_경로_변수에도_설명이_있다")
	void 파라미터에도_설명() {
		List<String> missing = new ArrayList<>();

		doc.path("paths").fields().forEachRemaining(path ->
				path.getValue().fields().forEachRemaining(operation -> {
					JsonNode params = operation.getValue().path("parameters");
					for (JsonNode param : params) {
						if (!param.hasNonNull("description")) {
							missing.add(path.getKey() + " " + operation.getKey()
									+ " ?" + param.path("name").asText());
						}
					}
				}));

		// q 처럼 한 글자짜리는 설명이 없으면 무엇을 넣어야 할지 알 수 없다
		assertThat(missing)
				.as("설명이 빠진 파라미터: %s", missing)
				.isEmpty();
	}

	// ---------------------------------------------------------------- 표본 확인

	@Test
	@DisplayName("헷갈리기_쉬운_이름들에_설명이_붙어_있다")
	void 짧은_이름들() {
		Map<String, String> expected = Map.of(
				"StoreSeller.bio", "소개",
				"StoreSeller.publicContact", "문의",
				"StoreItem.dDay", "마감",
				"StoreItem.price", "옵션",
				"WishlistResponse.saleFormIds", "찜",
				"TabCounts.secondUnpaid", "청구",
				"SellerOrderItem.title", "외 N건"
		);

		expected.forEach((key, keyword) -> {
			String[] parts = key.split("\\.");
			JsonNode property = doc.path("components").path("schemas")
					.path(parts[0]).path("properties").path(parts[1]);

			assertThat(property.path("description").asText())
					.as("%s 의 설명", key)
					.contains(keyword);
		});
	}

	@Test
	@DisplayName("엔드포인트에_요약이_붙어_있다")
	void 엔드포인트_요약() {
		List<String> missing = new ArrayList<>();

		doc.path("paths").fields().forEachRemaining(path ->
				path.getValue().fields().forEachRemaining(operation -> {
					if (!operation.getValue().hasNonNull("summary")) {
						missing.add(operation.getKey() + " " + path.getKey());
					}
				}));

		// 요약이 없으면 스웨거 목록에 메서드 이름이 그대로 나온다
		assertThat(missing)
				.as("요약이 빠진 엔드포인트: %s", missing)
				.isEmpty();
	}
}
