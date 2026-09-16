package store.moeum.moeum.dev.console;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code docs/status.html} 을 읽는다. <b>로컬 프로파일 전용.</b>
 *
 * 현황판은 사람이 손으로 갱신하는 문서다. 그 숫자를 콘솔에 옮겨 적으면 두 곳이 갈라지므로
 * 원본을 그대로 읽는다 — 현황판을 고치면 콘솔도 따라 바뀐다.
 *
 * 파싱은 정규식으로 한다. 대상이 우리가 쓴 파일 하나뿐이고 형식이 고정돼 있어서
 * HTML 파서를 의존성으로 들일 이유가 없다. 형식이 바뀌면 콘솔은 "읽지 못했다"만 말하고
 * 나머지 화면은 그대로 뜬다.
 */
@Slf4j
@Component
@Profile("local")
public class RoadmapReader {

	/** 프로젝트 루트가 어디로 잡히든 찾아낸다 (bootRun 은 moeum/, IDE 는 저장소 루트) */
	private static final List<String> CANDIDATES = List.of(
			"docs/status.html", "../docs/status.html", "../../docs/status.html");

	private static final Pattern TOTAL = Pattern.compile("id=\"totalNum\">(\\d+)");
	private static final Pattern AS_OF = Pattern.compile("<span>(\\d{4}-\\d{2}-\\d{2}) 기준</span>");
	private static final Pattern DOMAIN = Pattern.compile(
			"name:\\s*\"([^\"]+)\",\\s*pct:\\s*(\\d+),\\s*weight:\\s*(\\d+)");

	public RoadmapProgress read() {
		Path path = locate();
		if (path == null) {
			return RoadmapProgress.unavailable("docs/status.html 을 찾지 못했다");
		}
		try {
			String html = Files.readString(path, StandardCharsets.UTF_8);
			return new RoadmapProgress(true, path.toString(),
					intOf(TOTAL, html), stringOf(AS_OF, html), domains(html));
		}
		catch (IOException e) {
			log.debug("콘솔: 현황판 읽기 실패", e);
			return RoadmapProgress.unavailable(e.getMessage());
		}
	}

	/** 현황판 원본. 콘솔에서 새 탭으로 열어 주기 위해 그대로 내려보낸다 */
	public String html() throws IOException {
		Path path = locate();
		if (path == null) {
			throw new IOException("docs/status.html 을 찾지 못했다");
		}
		return Files.readString(path, StandardCharsets.UTF_8);
	}

	private Path locate() {
		for (String candidate : CANDIDATES) {
			Path path = Path.of(candidate).toAbsolutePath().normalize();
			if (Files.isRegularFile(path)) {
				return path;
			}
		}
		return null;
	}

	/**
	 * 도메인 머리 부분을 모두 찾고, 머리와 머리 사이 구간에서 항목 상태를 센다.
	 * 항목은 {@code ["제목", "설명", "done"]} 꼴이라 닫는 대괄호까지 붙여 세면 오탐이 없다.
	 */
	private List<RoadmapProgress.Domain> domains(String html) {
		List<int[]> spans = new ArrayList<>();
		List<String[]> heads = new ArrayList<>();

		Matcher matcher = DOMAIN.matcher(html);
		while (matcher.find()) {
			spans.add(new int[] { matcher.end(), 0 });
			heads.add(new String[] { matcher.group(1), matcher.group(2), matcher.group(3) });
			if (spans.size() > 1) {
				spans.get(spans.size() - 2)[1] = matcher.start();
			}
		}
		if (spans.isEmpty()) {
			return List.of();
		}
		spans.get(spans.size() - 1)[1] = html.length();

		List<RoadmapProgress.Domain> domains = new ArrayList<>(heads.size());
		for (int i = 0; i < heads.size(); i++) {
			String body = html.substring(spans.get(i)[0], spans.get(i)[1]);
			String name = heads.get(i)[0];
			domains.add(new RoadmapProgress.Domain(
					name, slug(name),
					Integer.parseInt(heads.get(i)[1]),
					Integer.parseInt(heads.get(i)[2]),
					count(body, "\"done\"]"),
					count(body, "\"part\"]"),
					count(body, "\"check\"]"),
					count(body, "\"todo\"]")));
		}
		return domains;
	}

	/** 현황판의 details id 와 같은 규칙이어야 링크가 걸린다 */
	static String slug(String name) {
		return "d-" + name.replaceAll("[^0-9A-Za-z가-힣]+", "-").replaceAll("^-|-$", "");
	}

	private int count(String text, String needle) {
		int found = 0;
		int at = text.indexOf(needle);
		while (at >= 0) {
			found++;
			at = text.indexOf(needle, at + needle.length());
		}
		return found;
	}

	private int intOf(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	private String stringOf(Pattern pattern, String html) {
		Matcher matcher = pattern.matcher(html);
		return matcher.find() ? matcher.group(1) : null;
	}
}
