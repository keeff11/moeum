package store.moeum.moeum.global.error;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 운영 컨트롤러까지 스캔하지 않도록 범위를 좁힌다. 여기서 볼 것은 예외 -> 응답 변환뿐이다
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("BusinessException은_ErrorCode의_상태값으로_나간다")
	void BusinessException은_ErrorCode의_상태값으로_나간다() throws Exception {
		mockMvc.perform(get("/test/business"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("판매 폼이 없습니다."))
				.andExpect(jsonPath("$.path").value("/test/business"))
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	@DisplayName("본문_검증_실패는_필드별_사유를_담는다")
	void 본문_검증_실패는_필드별_사유를_담는다() throws Exception {
		mockMvc.perform(post("/test/body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"qty\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.fieldErrors.length()").value(2));
	}

	@Test
	@DisplayName("필수_파라미터가_없으면_MISSING_PARAMETER다")
	void 필수_파라미터가_없으면_MISSING_PARAMETER다() throws Exception {
		mockMvc.perform(get("/test/param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("formId"));
	}

	@Test
	@DisplayName("파라미터_타입이_틀리면_INVALID_TYPE이다")
	void 파라미터_타입이_틀리면_INVALID_TYPE이다() throws Exception {
		mockMvc.perform(get("/test/param").param("formId", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_TYPE"));
	}

	@Test
	@DisplayName("본문이_깨지면_MALFORMED_BODY이고_본문_조각을_되돌려주지_않는다")
	void 본문이_깨지면_MALFORMED_BODY이고_본문_조각을_되돌려주지_않는다() throws Exception {
		mockMvc.perform(post("/test/body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_BODY"))
				.andExpect(jsonPath("$.message").value(ErrorCode.MALFORMED_BODY.message()));
	}

	@Test
	@DisplayName("예상하지_못한_예외는_내부_메시지를_노출하지_않는다")
	void 예상하지_못한_예외는_내부_메시지를_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.message()));
	}

	@TestConfiguration
	@RestController
	static class TestController {

		@GetMapping("/test/business")
		void business() {
			throw new BusinessException(ErrorCode.NOT_FOUND, "판매 폼이 없습니다.");
		}

		@GetMapping("/test/param")
		void param(@RequestParam Long formId) {
		}

		@PostMapping("/test/body")
		void body(@jakarta.validation.Valid @RequestBody Request request) {
		}

		@GetMapping("/test/boom")
		void boom() {
			// point3 토큰이 예외 메시지에 섞여 나가는 상황을 흉내낸다
			throw new IllegalStateException("token=secret-abcdef payer_id=1234567890");
		}

		record Request(@NotBlank String name, @Min(1) int qty) {
		}
	}
}
