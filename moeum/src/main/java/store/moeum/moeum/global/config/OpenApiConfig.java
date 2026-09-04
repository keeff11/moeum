package store.moeum.moeum.global.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import store.moeum.moeum.global.error.ErrorResponse;

import java.util.List;
import java.util.Map;

/**
 * OpenAPI 문서 설정.
 *
 * 자동 생성 문서는 "계약의 현재 모양"만 담는다. "왜 이렇게 정했는가"는 docs/api-spec.md 쪽이다.
 * 운영 프로파일에서는 application.yml 이 springdoc 을 통째로 끈다 —
 * 관리자 API 까지 그대로 노출되기 때문이다.
 */
@Configuration
public class OpenApiConfig {

	private static final String SESSION_SCHEME = "sessionCookie";
	private static final String ERROR_SCHEMA_REF = "#/components/schemas/ErrorResponse";

	/**
	 * 로그인이 필요한 경로. 여기에 걸리는 오퍼레이션에만 401 과 인증 요구를 붙인다.
	 * 새 컨트롤러에 @LoginUser 를 쓰면 여기에도 접두어를 추가해야 한다.
	 */
	private static final List<String> SECURED_PREFIXES =
			List.of("/seller", "/me", "/checkout-sessions");

	@Bean
	public OpenAPI moeumOpenApi() {
		SecurityScheme sessionCookie = new SecurityScheme()
				.type(SecurityScheme.Type.APIKEY)
				.in(SecurityScheme.In.COOKIE)
				.name("MOEUM_SESSION")
				.description("카카오 로그인 후 발급되는 httpOnly 세션 쿠키. JWT 가 아니다");

		return new OpenAPI()
				.info(new Info()
						.title("moeum-api")
						.version("v0.0.1")
						.description("""
								공동구매 · 단독판매 플랫폼 API.

								- 인증은 세션 쿠키(httpOnly)다. 카카오 access/refresh 토큰은 서버에만 보관한다.
								- 로그인은 `GET /auth/kakao/login` 으로 시작한다. 브라우저 리다이렉트 흐름이라
								  이 화면의 Try it out 으로는 끝까지 진행되지 않는다.
								- 실패 응답은 전부 `ErrorResponse` 한 가지 형식이다.
								"""))
				.components(new Components().addSecuritySchemes(SESSION_SCHEME, sessionCookie));
	}

	/**
	 * 공통 실패 응답을 모든 오퍼레이션에 붙인다.
	 *
	 * ErrorResponse 는 @RestControllerAdvice 에서만 나가서 컨트롤러 시그니처에 안 잡힌다.
	 * 메서드마다 @ApiResponse 를 손으로 다는 대신 여기서 한 번에 채운다 —
	 * 손으로 달면 새 엔드포인트에서 빠뜨린다.
	 */
	@Bean
	public OpenApiCustomizer commonErrorResponses() {
		return openApi -> {
			registerErrorSchema(openApi);

			openApi.getPaths().forEach((path, pathItem) ->
					pathItem.readOperationsMap().forEach((httpMethod, operation) -> {
						ApiResponses responses = operation.getResponses();

						putIfAbsent(responses, "400", "요청 값이 올바르지 않다");
						putIfAbsent(responses, "500", "서버 오류. 메시지는 고정 문구이며 traceId 로 로그를 찾는다");

						if (isSecured(path)) {
							putIfAbsent(responses, "401", "로그인이 필요하다");
							operation.addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME));
						}
						tagIfMissing(operation, path);
					}));
		};
	}

	/** ErrorResponse 를 참조하는 컨트롤러 메서드가 없어서 직접 components 에 등록한다 */
	private void registerErrorSchema(OpenAPI openApi) {
		if (openApi.getComponents() == null) {
			openApi.components(new Components());
		}
		ResolvedSchema resolved = ModelConverters.getInstance()
				.readAllAsResolvedSchema(ErrorResponse.class);

		if (resolved == null || resolved.referencedSchemas == null) {
			return;
		}
		for (Map.Entry<String, Schema> entry : resolved.referencedSchemas.entrySet()) {
			openApi.getComponents().addSchemas(entry.getKey(), entry.getValue());
		}
	}

	private void putIfAbsent(ApiResponses responses, String status, String description) {
		if (responses.containsKey(status)) {
			return;
		}
		responses.addApiResponse(status, new ApiResponse()
				.description(description)
				.content(new Content().addMediaType("application/json",
						new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA_REF)))));
	}

	private boolean isSecured(String path) {
		return SECURED_PREFIXES.stream().anyMatch(path::startsWith);
	}

	/** 컨트롤러에 @Tag 가 없으면 경로 첫 조각으로 묶어 준다 */
	private void tagIfMissing(Operation operation, String path) {
		if (operation.getTags() != null && !operation.getTags().isEmpty()) {
			return;
		}
		String[] segments = path.split("/");
		operation.addTagsItem(segments.length > 1 ? segments[1] : "root");
	}
}
