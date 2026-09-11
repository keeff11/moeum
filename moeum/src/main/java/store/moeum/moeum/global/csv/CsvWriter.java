package store.moeum.moeum.global.csv;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 엑셀에서 열리는 CSV 를 만든다.
 *
 * 이 클래스가 있는 이유는 CSV 가 어려워서가 아니라 <b>두 가지를 빼먹기 쉬워서</b>다.
 *
 * <b>1. BOM 이 없으면 한글이 깨진다.</b> 셀러가 이 파일을 여는 도구는 사실상 엑셀 하나인데,
 * 엑셀은 BOM 없는 UTF-8 을 시스템 기본 인코딩(한국어 윈도우면 EUC-KR)으로 읽는다.
 * 3바이트짜리 처리지만 빠뜨리면 파일이 통째로 못 쓰게 된다.
 *
 * <b>2. CSV 인젝션.</b> {@code =} · {@code +} · {@code -} · {@code @} 로 시작하는 칸을
 * 엑셀이 <b>수식으로 실행한다.</b> 상품명 · 옵션명은 셀러가 입력한 자유 텍스트이고,
 * 발주서는 셀러가 <b>공장에 보내는</b> 파일이라 남의 컴퓨터에서 열린다.
 * 앞에 작은따옴표를 붙여 글자로 고정한다.
 */
public class CsvWriter {

	/** 엑셀이 수식으로 읽기 시작하는 글자들. 탭과 CR 도 같은 취급을 받는다 */
	private static final String FORMULA_PREFIXES = "=+-@\t\r";

	/** 음수도 이 목록에 걸린다. 숫자는 수식이 아니므로 예외로 둔다 */
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

	/** 엑셀은 CRLF 를 기대한다 */
	private static final String NEW_LINE = "\r\n";

	private final StringBuilder body = new StringBuilder();

	/** 한 줄을 쓴다. 칸 수가 줄마다 달라도 막지 않는다 — 부르는 쪽이 맞춘다 */
	public CsvWriter row(Object... cells) {
		for (int i = 0; i < cells.length; i++) {
			if (i > 0) {
				body.append(',');
			}
			body.append(escape(cells[i] == null ? "" : cells[i].toString()));
		}
		body.append(NEW_LINE);
		return this;
	}

	/** BOM 을 붙여 바이트로 만든다. 응답에 그대로 실으면 된다 */
	public byte[] toBytes() {
		return ("\uFEFF" + body).getBytes(StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		String cell = value;

		if (!cell.isEmpty()
				&& FORMULA_PREFIXES.indexOf(cell.charAt(0)) >= 0
				&& !NUMBER.matcher(cell).matches()) {
			cell = "'" + cell;
		}

		if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
			cell = "\"" + cell.replace("\"", "\"\"") + "\"";
		}
		return cell;
	}
}
