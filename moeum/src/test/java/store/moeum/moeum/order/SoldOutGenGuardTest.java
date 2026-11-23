package store.moeum.moeum.order;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.ProductOptionRepository;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고를 다시 늘리는 경로가 품절 gen 을 빠뜨리지 않게 막는다 (D-081).
 *
 * <p>gen 품절 캐시(D-078)는 "재고가 돌아오는 모든 경로가 커밋 직전에 폼 gen 을 올린다" 는 약속 위에 서 있다.
 * 한 경로라도 빠지면 그 경로로 재고가 돌아와도 품절 표시가 유효하게 남아, TTL 동안 재고가 있는데 409 가 난다.
 * 초과 판매는 아니라서 다른 테스트로는 드러나지 않는다 — 그래서 코드 구조로 검사한다.
 *
 * <p>세 겹이다.
 * <ol>
 *   <li><b>재고 쓰기 분류</b> — 폼 · 옵션 리포지토리의 모든 UPDATE 와, 엔티티에서 살 수 있는지를 정하는 필드
 *       (status · stock_max · closes_at)를 바꾸는 모든 메서드를 "늘림" 과 "안 늘림" 으로 나눠 적어 둔다.
 *       새 쓰기가 생기면 분류되지 않았다고 실패한다 — 만든 사람이 어느 쪽인지 정하게 하는 것이 목적이다.</li>
 *   <li><b>늘림 호출 옆의 gen</b> — "늘림" 으로 분류된 메서드를 부르는 메서드는 같은 메서드 안에서
 *       {@link SoldOutCache#bumpBeforeCommit} 도 불러야 한다.</li>
 *   <li><b>우회 SQL</b> — 리포지토리를 거치지 않고 sale_form · product_option 을 바꾸는 SQL 문자열이 있으면 실패한다.
 *       1 · 2 는 리포지토리 메서드와 엔티티만 보므로, JdbcTemplate 로 직접 쓰면 둘 다 빠져나간다.</li>
 * </ol>
 */
class SoldOutGenGuardTest {

	private static final String ROOT_PACKAGE = "store.moeum.moeum";

	/** 재고를 다시 늘리는 리포지토리 메서드. 부르는 쪽이 gen 을 올려야 한다 */
	private static final Map<Class<?>, Set<String>> REPOSITORY_RESTORING = Map.of(
			SaleFormRepository.class, Set.of("releaseHold", "restoreSold"),
			ProductOptionRepository.class, Set.of("releaseHold", "restoreSold"));

	/**
	 * 재고를 늘리지 않는 리포지토리 UPDATE. 왜 아닌지 적어 둔다.
	 * <ul>
	 *   <li>hold — 줄이는 쪽이다. 품절 표시를 틀리게 만들지 않는다</li>
	 *   <li>commitHold — held 에서 sold 로 옮길 뿐 남은 재고(stock_max - held - sold)는 그대로다</li>
	 *   <li>closeExpired — 마감은 되돌릴 수 없다. 살 수 있게 되는 일이 없다</li>
	 * </ul>
	 */
	private static final Map<Class<?>, Set<String>> REPOSITORY_OTHER = Map.of(
			SaleFormRepository.class, Set.of("hold", "commitHold", "closeExpired"),
			ProductOptionRepository.class, Set.of("hold", "commitHold"));

	/**
	 * 조건부 UPDATE(hold)의 WHERE 가 보는 엔티티 필드. 이걸 바꾸면 살 수 있는지가 바뀐다.
	 * held · sold 는 엔티티에서 쓸 수 없는 컬럼이라(updatable = false) 리포지토리 쪽에서 본다.
	 */
	private static final Map<Class<?>, Set<String>> PURCHASABILITY_FIELDS = Map.of(
			SaleForm.class, Set.of("status", "stockMax", "closesAt"),
			ProductOption.class, Set.of("stockMax"));

	/** 위 필드를 바꿔 재고를 다시 늘릴 수 있는 엔티티 메서드. 부르는 쪽이 gen 을 올려야 한다 */
	private static final Map<Class<?>, Set<String>> ENTITY_RESTORING = Map.of(
			SaleForm.class, Set.of("startSelling", "update", "syncStockMaxToOptions"),
			ProductOption.class, Set.of("changeStock"));

	/**
	 * 위 필드를 바꾸지만 재고를 늘리지 않는 엔티티 메서드.
	 * <ul>
	 *   <li>pause — 막는 쪽이다. 일시중지 실패는 품절 표시를 세우지도 않는다</li>
	 *   <li>close — 마감은 되돌릴 수 없다</li>
	 * </ul>
	 */
	private static final Map<Class<?>, Set<String>> ENTITY_OTHER = Map.of(
			SaleForm.class, Set.of("pause", "close"),
			ProductOption.class, Set.of());

	/**
	 * 리포지토리 밖에서 재고 테이블을 직접 바꿔도 되는 곳.
	 * dev 패키지는 전부 {@code @Profile("local")} 이다 — 부하 테스트 · 데모용으로 새 폼을 만들어 쓰므로 남은 품절 표시가 없다.
	 */
	private static final List<String> RAW_SQL_ALLOWED = List.of(
			"saleform/domain/SaleFormRepository.java",
			"saleform/domain/ProductOptionRepository.java",
			"dev/");

	private static final Pattern RAW_STOCK_WRITE = Pattern.compile(
			"UPDATE\\s+(sale_form|product_option)\\b|update\\s+(SaleForm|ProductOption)\\b",
			Pattern.CASE_INSENSITIVE);

	private static JavaClasses classes;

	@BeforeAll
	static void importClasses() {
		classes = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages(ROOT_PACKAGE);
	}

	@Test
	@DisplayName("폼_옵션_리포지토리의_UPDATE는_모두_재고를_늘리는지_아닌지_분류돼_있다")
	void 리포지토리_UPDATE_분류() {
		List<String> problems = new ArrayList<>();
		for (Class<?> repository : REPOSITORY_RESTORING.keySet()) {
			Set<String> modifying = Arrays.stream(repository.getDeclaredMethods())
					.filter(method -> method.isAnnotationPresent(Modifying.class))
					.map(Method::getName)
					.collect(Collectors.toCollection(TreeSet::new));
			problems.addAll(classify(repository, modifying,
					REPOSITORY_RESTORING.get(repository), REPOSITORY_OTHER.get(repository)));
		}

		assertThat(problems)
				.as("""
						재고 테이블을 바꾸는 쿼리가 분류되지 않았거나 사라졌다.
						재고를 다시 늘리는 쿼리면 REPOSITORY_RESTORING 에 넣고 부르는 쪽에서 SoldOutCache.bumpBeforeCommit 을 부른다.
						아니면 REPOSITORY_OTHER 에 이유와 함께 넣는다 (D-078 · D-081).""")
				.isEmpty();
	}

	@Test
	@DisplayName("살_수_있는지를_정하는_엔티티_필드를_바꾸는_메서드는_모두_분류돼_있다")
	void 엔티티_필드_쓰기_분류() {
		List<String> problems = new ArrayList<>();
		for (Class<?> entity : PURCHASABILITY_FIELDS.keySet()) {
			Set<String> fields = PURCHASABILITY_FIELDS.get(entity);
			Set<String> writers = classes.get(entity).getMethods().stream()
					.filter(method -> method.getFieldAccesses().stream()
							.anyMatch(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET
									&& access.getTarget().getOwner().isEquivalentTo(entity)
									&& fields.contains(access.getTarget().getName())))
					.map(method -> originName(method.getName()))
					.collect(Collectors.toCollection(TreeSet::new));
			problems.addAll(classify(entity, writers, ENTITY_RESTORING.get(entity), ENTITY_OTHER.get(entity)));
		}

		assertThat(problems)
				.as("""
						살 수 있는지를 정하는 필드(status · stockMax · closesAt)를 바꾸는 메서드가 분류되지 않았거나 사라졌다.
						재고를 다시 늘릴 수 있으면 ENTITY_RESTORING 에 넣고 부르는 쪽에서 SoldOutCache.bumpBeforeCommit 을 부른다.
						아니면 ENTITY_OTHER 에 이유와 함께 넣는다 (D-078 · D-081).""")
				.isEmpty();
	}

	@Test
	@DisplayName("재고를_다시_늘리는_메서드를_부르는_곳은_같은_메서드에서_gen도_올린다")
	void 늘림_호출_옆에_gen() {
		Map<Class<?>, Set<String>> restoring = new LinkedHashMap<>(REPOSITORY_RESTORING);
		restoring.putAll(ENTITY_RESTORING);

		// 람다 안의 호출은 컴파일러가 lambda$바깥메서드$N 으로 빼낸다. 바깥 메서드 하나로 묶어서 본다
		Map<String, List<JavaCodeUnit>> byOrigin = new LinkedHashMap<>();
		for (JavaClass javaClass : classes) {
			if (PURCHASABILITY_FIELDS.containsKey(javaClass.reflect())) {
				continue;   // 엔티티 안에서 서로 부르는 것은 위의 분류 검사가 본다
			}
			for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
				String key = javaClass.getName() + "#" + originName(unit.getName());
				byOrigin.computeIfAbsent(key, ignored -> new ArrayList<>()).add(unit);
			}
		}

		List<String> missing = new ArrayList<>();
		byOrigin.forEach((origin, units) -> {
			List<String> restoringCalls = units.stream()
					.flatMap(unit -> unit.getMethodCallsFromSelf().stream())
					.map(JavaAccess::getTarget)
					.filter(target -> isRestoring(target, restoring))
					.map(target -> target.getOwner().getSimpleName() + "." + target.getName())
					.distinct()
					.toList();
			if (restoringCalls.isEmpty()) {
				return;
			}
			boolean bumps = units.stream()
					.flatMap(unit -> unit.getMethodCallsFromSelf().stream())
					.map(JavaAccess::getTarget)
					.anyMatch(target -> target.getOwner().isEquivalentTo(SoldOutCache.class)
							&& target.getName().equals("bumpBeforeCommit"));
			if (!bumps) {
				missing.add(origin + " → " + restoringCalls);
			}
		});

		assertThat(missing)
				.as("""
						재고를 다시 늘리는데 품절 gen 을 올리지 않는다. 이대로면 품절 표시가 TTL 동안 유효하게 남아
						재고가 있는데 409 가 난다. 같은 메서드에서 SoldOutCache.bumpBeforeCommit(폼 id) 를 부른다 (D-078 · D-081).""")
				.isEmpty();
	}

	@Test
	@DisplayName("리포지토리를_거치지_않고_재고_테이블을_바꾸는_SQL이_없다")
	void 우회_SQL_없음() throws IOException {
		Path sourceRoot = Path.of("src/main/java", ROOT_PACKAGE.replace('.', '/'));
		assertThat(sourceRoot).as("소스 위치 — 테스트는 moeum 모듈 디렉터리에서 돈다").isDirectory();

		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(sourceRoot)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
				if (RAW_SQL_ALLOWED.stream().anyMatch(relative::startsWith)) {
					continue;
				}
				Matcher matcher = RAW_STOCK_WRITE.matcher(Files.readString(file));
				while (matcher.find()) {
					found.add(relative + ": " + matcher.group());
				}
			}
		}

		assertThat(found)
				.as("""
						리포지토리 밖에서 sale_form · product_option 을 바꾸는 SQL 이 있다. 이 경로는 위 분류 검사에 걸리지 않는다.
						SaleFormRepository · ProductOptionRepository 에 메서드로 옮겨 분류하거나, 로컬 전용이면 RAW_SQL_ALLOWED 에 넣는다 (D-081).""")
				.isEmpty();
	}

	/** 실제 메서드 집합과 분류표를 맞춰 본다. 분류 안 된 것과, 분류표에만 남은 것을 둘 다 잡는다 */
	private static List<String> classify(Class<?> owner, Set<String> actual, Set<String> restoring, Set<String> other) {
		List<String> problems = new ArrayList<>();
		for (String name : actual) {
			if (!restoring.contains(name) && !other.contains(name)) {
				problems.add(owner.getSimpleName() + "." + name + " — 분류되지 않음");
			}
		}
		Stream.concat(restoring.stream(), other.stream())
				.filter(name -> !actual.contains(name))
				.sorted()
				.forEach(name -> problems.add(owner.getSimpleName() + "." + name + " — 분류표에만 있음 (이름이 바뀌었거나 지워졌다)"));
		return problems;
	}

	private static boolean isRestoring(AccessTarget.MethodCallTarget target, Map<Class<?>, Set<String>> restoring) {
		return restoring.entrySet().stream()
				.anyMatch(entry -> target.getOwner().isEquivalentTo(entry.getKey())
						&& entry.getValue().contains(target.getName()));
	}

	/** lambda$update$0 → update. 람다가 아니면 그대로 */
	private static String originName(String name) {
		if (name.startsWith("lambda$")) {
			int end = name.indexOf('$', "lambda$".length());
			return end > 0 ? name.substring("lambda$".length(), end) : name;
		}
		return name;
	}
}
