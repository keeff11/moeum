package store.moeum.moeum.global.csv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엑셀에서 열리는 CSV (D-045).
 *
 * 여기서 보는 둘은 만들고 나서야 발견되는 종류다 — BOM 을 빠뜨리면 한글이 통째로 깨지고,
 * 수식 이스케이프를 빠뜨리면 셀러가 공장에 보낸 파일이 남의 엑셀에서 실행된다.
 */
class CsvWriterTest {

	@Test
	@DisplayName("BOM_이_붙는다")
	void BOM() {
		// 엑셀은 BOM 없는 UTF-8 을 EUC-KR 로 읽는다. 이 3바이트가 없으면 한글이 전부 깨진다
		byte[] bytes = new CsvWriter().row("상품명").toBytes();

		assertThat(bytes[0]).isEqualTo((byte) 0xEF);
		assertThat(bytes[1]).isEqualTo((byte) 0xBB);
		assertThat(bytes[2]).isEqualTo((byte) 0xBF);
		assertThat(text(bytes)).isEqualTo("\uFEFF상품명\r\n");
	}

	@Test
	@DisplayName("수식으로_시작하는_값은_글자로_고정한다")
	void CSV_인젝션() {
		// 옵션명은 셀러가 입력한 자유 텍스트이고, 발주서는 공장으로 보내진다
		String csv = text(new CsvWriter()
				.row("=1+1", "+42", "-SUM(A1)", "@cmd", "블루")
				.toBytes());

		assertThat(csv).isEqualTo("\uFEFF'=1+1,'+42,'-SUM(A1),'@cmd,블루\r\n");
	}

	@Test
	@DisplayName("음수는_수식이_아니라_숫자로_둔다")
	void 음수는_그대로() {
		assertThat(text(new CsvWriter().row(-5, 0, 12).toBytes()))
				.isEqualTo("\uFEFF-5,0,12\r\n");
	}

	@Test
	@DisplayName("쉼표_따옴표_줄바꿈이_들어간_값은_감싼다")
	void 따옴표_처리() {
		String csv = text(new CsvWriter()
				.row("블루, 라지", "12\"", "두\n줄")
				.toBytes());

		assertThat(csv).isEqualTo("\uFEFF\"블루, 라지\",\"12\"\"\",\"두\n줄\"\r\n");
	}

	@Test
	@DisplayName("null_은_빈_칸이_된다")
	void null_처리() {
		assertThat(text(new CsvWriter().row("A", null, "C").toBytes()))
				.isEqualTo("\uFEFFA,,C\r\n");
	}

	@Test
	@DisplayName("줄_끝은_CRLF_다")
	void 줄바꿈() {
		// 엑셀이 기대하는 형식이다
		assertThat(text(new CsvWriter().row("a").row("b").toBytes()))
				.isEqualTo("\uFEFFa\r\nb\r\n");
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
