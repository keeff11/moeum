package store.moeum.moeum.order;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 품절 단락 — 품절이 확인된 폼 · 옵션의 주문을 DB 커넥션을 받기 전에 409 로 돌려보낸다 (D-078).
 *
 * <b>판정은 여전히 DB 조건부 UPDATE 만 한다.</b> 이 캐시는 "이미 품절로 확인된 요청" 을 거를 뿐,
 * "팔아도 된다" 를 결정하지 않는다. 그래서 캐시가 어느 쪽으로 틀려도 초과 판매는 생기지 않는다.
 * 틀릴 수 있는 방향은 "재고가 있는데 못 사는 쪽" 이고, 아래 세 장치가 그 틈을 막는다.
 *
 * <h3>키</h3>
 * <pre>
 *   gen:form:{폼}           정수. 폼의 재고가 다시 늘어난 횟수. 늘 때만 +1, 주문 성공 때는 그대로
 *   soldout:form:{폼}       값 = 품절을 확인할 때의 gen. TTL 2초
 *   soldout:option:{옵션}   값 = 품절을 확인할 때의 (그 옵션이 속한 폼의) gen. TTL 2초
 *   held:{kakaoId}:{폼}     이 구매자가 이 폼에 홀드를 잡았다. TTL 30분(홀드와 같다). 이 폼은 확인을 건너뛴다 (D-089)
 * </pre>
 * 홀드를 쥔 구매자가 다시 담으면 DB 에서는 이어받기(D-037)로 기존 홀드를 돌려받거나, 교체(D-073)로 자기 홀드를
 * 먼저 풀고 다시 잡는다. 남이 세운 품절 표시는 그 사람 몫까지는 모르므로, 쥔 폼에 대해서는 표시를 믿지 않고 DB 로 보낸다.
 * 폼마다 따로 두는 이유 — 선택 하나만 적으면 선택을 바꾸거나 다른 폼을 따로 담는 순간 덮어써져, 쥐고 있는 자리를
 * 품절 표시가 막는다.
 * gen 을 폼 단위로 두는 이유 — 폼 재고는 옵션들이 나눠 쓴다. A 옵션 홀드가 풀려도 B 옵션을 살 수 있게 되므로,
 * 폼 안의 어떤 복구든 폼의 모든 품절 표시를 무효로 만들어야 한다.
 *
 * <h3>틈을 막는 장치</h3>
 * <ol>
 *   <li><b>표시는 0행을 받은 요청이 세운다.</b> 마지막 1개를 가져간 트랜잭션이 세우면 롤백될 때 틀린 표시가 남는다.
 *       0행 UPDATE 는 바꾼 게 없어 롤백될 것이 없고, 커밋된 최신 값을 보고(잠금이 있으면 기다리고) 낸 결과다.</li>
 *   <li><b>표시 값은 DB 를 보기 전에 읽은 gen 이다.</b> 확인과 표시 사이에 재고가 돌아오면 gen 이 올라가 있으므로,
 *       늦게 도착한 표시는 값이 맞지 않아 무시된다. 그래서 복구 쪽은 표시를 지우지 않고 gen 만 올린다.</li>
 *   <li><b>gen 은 재고 UPDATE 뒤, 커밋 직전에 올린다</b> ({@link #bumpBeforeCommit}). 잠금을 쥔 채로 올리므로
 *       새 gen 을 읽은 요청은 그 잠금을 기다렸다가 복구가 반영된 재고를 본다. 먼저 올리면 복구 전 재고를 보고
 *       새 gen 으로 표시를 세울 수 있다. 커밋 뒤에 올리면 "재고는 있는데 표시는 유효한" 틈이 생긴다.</li>
 * </ol>
 * gen 올리기가 실패하면 커밋 직전에 한 번, 커밋 뒤 백그라운드에서 TTL + 1초까지 다시 올린다 (D-080).
 * 그래도 남는 경우(재시도까지 실패 · 앱을 거치지 않은 DB 수정)는 TTL 이 상한이다.
 * 재고를 다시 늘리는 새 경로가 이 메서드를 빠뜨리면 SoldOutGenGuardTest 가 실패한다 (D-081).
 *
 * <h3>Redis 장애</h3>
 * 확인이 실패하면 통과시킨다(fail-open). 이 캐시용 연결은 세션과 따로 두고 명령 타임아웃을 짧게 잡는다 —
 * gen 올리기는 재고 행 잠금을 쥔 채로 하므로, 세션용 2초 타임아웃을 그대로 쓰면 인기 행을 2초 붙잡는다.
 */
@Slf4j
@Component
public class SoldOutCache implements DisposableBean {

	static final String GEN_FORM = "moeum:stock:gen:form:";
	static final String SOLDOUT_FORM = "moeum:stock:soldout:form:";
	static final String SOLDOUT_OPTION = "moeum:stock:soldout:option:";
	static final String HELD = "moeum:stock:held:";

	private static final String SOLD_OUT_MESSAGE = "재고가 부족하거나 판매가 마감되었습니다.";

	/** 커밋 뒤 gen 다시 올리기 간격 (D-080) */
	static final Duration RETRY_INTERVAL = Duration.ofMillis(200);

	private final boolean enabled;
	private final Duration ttl;
	private final Duration heldTtl;
	private final LettuceConnectionFactory connectionFactory;
	private final StringRedisTemplate redis;
	private volatile Function<String, Long> incrementer;

	/** 커밋 뒤 gen 다시 올리기. 요청 스레드를 붙잡지 않으려고 따로 돈다. 한 번에 몇 건뿐이라 스레드 하나로 충분하다 */
	private final ScheduledExecutorService retrier = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "sold-out-gen-retry");
		thread.setDaemon(true);
		return thread;
	});

	/** 옵션 → 폼. 옵션이 다른 폼으로 옮겨 가는 일은 없어서 지우지 않는다. 주문 생성에서 채운다 */
	private final Map<Long, Long> formOfOption = new ConcurrentHashMap<>();

	@PersistenceContext
	private EntityManager entityManager;

	public SoldOutCache(RedisProperties redisProperties,
	                    @Value("${moeum.sold-out-cache.enabled:true}") boolean enabled,
	                    @Value("${moeum.sold-out-cache.ttl:2s}") Duration ttl,
	                    @Value("${moeum.sold-out-cache.command-timeout:100ms}") Duration commandTimeout) {
		this.enabled = enabled;
		this.ttl = ttl;
		this.heldTtl = Duration.ofMinutes(OrderCreator.HOLD_MINUTES);

		RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
				redisProperties.getHost(), redisProperties.getPort());
		server.setDatabase(redisProperties.getDatabase());
		if (redisProperties.getPassword() != null) {
			server.setPassword(redisProperties.getPassword());
		}
		// 빈으로 등록하지 않는다 — RedisConnectionFactory 빈이 하나 더 생기면 Boot 의 기본 연결(세션용)이 빠진다
		this.connectionFactory = new LettuceConnectionFactory(server,
				LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
		this.connectionFactory.afterPropertiesSet();
		this.connectionFactory.start();
		this.redis = new StringRedisTemplate(connectionFactory);
		this.incrementer = defaultIncrementer();
	}

	/** 표시를 세울 때 쓸 gen. 확인을 건너뛰었거나 Redis 를 못 읽었으면 비어 있다 — 그때는 표시를 세우지 않는다 */
	public record Ticket(Map<Long, String> genByForm) {

		static final Ticket NONE = new Ticket(Map.of());

		String genOf(Long saleFormId) {
			return genByForm.get(saleFormId);
		}
	}

	// ---------------------------------------------------------------- 주문 쪽

	/**
	 * 입구 확인. DB 커넥션을 받기 전에 부른다.
	 *
	 * @throws OutOfStockException 품절 표시가 지금 gen 과 같을 때 — 확인 뒤로 재고가 다시 늘어난 적이 없다
	 */
	public Ticket check(String kakaoId, OrderCreateRequest request) {
		if (!enabled) {
			return Ticket.NONE;
		}
		List<Long> optionIds = request.items().stream().map(OrderCreateRequest.Item::optionId).distinct().toList();
		// 폼을 아직 모르는 옵션(재시작 뒤 첫 주문)은 확인하지 못한다. 아는 것만 확인하고 나머지는 DB 에 맡긴다
		Map<Long, Long> known = new HashMap<>();
		for (Long optionId : optionIds) {
			Long formId = formOfOption.get(optionId);
			if (formId != null) {
				known.put(optionId, formId);
			}
		}
		List<Long> formIds = known.values().stream().distinct().toList();
		List<Long> knownOptions = new ArrayList<>(known.keySet());

		// 폼마다 [gen, 폼 품절 표시, 이 구매자의 홀드] 셋씩, 그 뒤에 옵션 품절 표시
		List<String> keys = new ArrayList<>();
		for (Long formId : formIds) {
			keys.add(GEN_FORM + formId);
			keys.add(SOLDOUT_FORM + formId);
			keys.add(heldKey(kakaoId, formId));
		}
		knownOptions.forEach(optionId -> keys.add(SOLDOUT_OPTION + optionId));
		if (keys.isEmpty()) {
			return Ticket.NONE;
		}

		List<String> values;
		try {
			values = redis.opsForValue().multiGet(keys);
		} catch (RuntimeException e) {
			log.warn("품절 표시 확인 실패 — DB 로 보낸다: {}: {}", e.getClass().getSimpleName(), e.getMessage());
			return Ticket.NONE;
		}
		if (values == null) {
			return Ticket.NONE;
		}

		Map<Long, String> genByForm = new HashMap<>();
		Map<Long, String> soldOutByForm = new HashMap<>();
		Set<Long> heldForms = new HashSet<>();
		int at = 0;
		for (Long formId : formIds) {
			genByForm.put(formId, Objects.requireNonNullElse(values.get(at++), "0"));
			soldOutByForm.put(formId, values.get(at++));
			if (values.get(at++) != null) {
				heldForms.add(formId);
			}
		}

		// 이 구매자가 홀드를 쥔 폼은 건너뛴다 (D-089). 이어받기나 교체로 자기 자리를 다시 쓸 수 있는데,
		// 남이 세운 표시는 그 자리까지 품절로 센다. 홀드가 이미 끝났어도 DB 로 갈 뿐이라 안전하다.
		// gen 은 그대로 ticket 에 담는다 — 자기 홀드를 풀고도 0행이면 남에게도 없는 것이라 표시를 세워도 맞다
		for (Long formId : formIds) {
			if (!heldForms.contains(formId) && genByForm.get(formId).equals(soldOutByForm.get(formId))) {
				throw new OutOfStockException(formId, SOLD_OUT_MESSAGE);
			}
		}
		for (Long optionId : knownOptions) {
			Long formId = known.get(optionId);
			String soldOut = values.get(at++);
			if (!heldForms.contains(formId) && genByForm.get(formId).equals(soldOut)) {
				throw new OutOfStockException(formId, SOLD_OUT_MESSAGE);
			}
		}
		return new Ticket(genByForm);
	}

	/**
	 * DB 가 0행을 돌려준 뒤(트랜잭션이 롤백된 뒤) 부른다. 입구에서 읽어 둔 gen 으로 표시를 세운다.
	 * 수량 2개 이상의 실패 · 일시중지는 예외에 품절 범위가 없어 세우지 않는다.
	 */
	public void markSoldOut(Ticket ticket, OutOfStockException e) {
		OutOfStockException.SoldOut soldOut = e.soldOut();
		if (!enabled || soldOut == null) {
			return;
		}
		String gen = ticket.genOf(soldOut.saleFormId());
		if (gen == null) {
			return;
		}
		String key = soldOut.optionId() == null
				? SOLDOUT_FORM + soldOut.saleFormId()
				: SOLDOUT_OPTION + soldOut.optionId();
		try {
			redis.opsForValue().set(key, gen, ttl);
		} catch (RuntimeException ex) {
			log.warn("품절 표시 저장 실패: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * 주문이 만들어진(또는 이어받은) 뒤 부른다. 홀드를 쥔 폼마다 남겨, 그 구매자가 다시 담을 때
	 * 품절 표시에 막히지 않고 이어받기 · 교체로 가게 한다 (D-089).
	 *
	 * 홀드가 이탈 · 만료로 먼저 끝나도 지우지 않는다. 그 구매자의 요청이 DB 로 한 번 더 갈 뿐이다.
	 *
	 * 파이프라인(executePipelined)을 쓰지 않는다 (D-090). 파이프라인은 전용 연결을 쓰는데 이 캐시는 연결 풀이 없어,
	 * 주문마다 Redis 연결을 새로 열고 닫았다. 폼은 대개 하나라 공유 연결로 하나씩 보낸다.
	 * 첫 실패에서 멈춘다 — Redis 가 느릴 때 폼 수만큼 타임아웃을 기다리지 않는다.
	 */
	public void rememberHeld(String kakaoId, Collection<Long> saleFormIds) {
		if (!enabled || saleFormIds.isEmpty()) {
			return;
		}
		try {
			for (Long formId : new LinkedHashSet<>(saleFormIds)) {
				redis.opsForValue().set(heldKey(kakaoId, formId), "1", heldTtl);
			}
		} catch (RuntimeException e) {
			log.warn("홀드 쥔 폼 저장 실패: {}: {}", e.getClass().getSimpleName(), e.getMessage());
		}
	}

	static String heldKey(String kakaoId, Long saleFormId) {
		return HELD + kakaoId + ":" + saleFormId;
	}

	/** 주문 생성에서 옵션을 읽을 때 부른다. 입구 확인이 옵션의 폼을 DB 없이 알아야 한다 */
	public void rememberForms(Map<SaleForm, List<ProductOption>> optionsByForm) {
		optionsByForm.forEach((form, options) ->
				options.forEach(option -> formOfOption.put(option.getId(), form.getId())));
	}

	// ---------------------------------------------------------------- 복구 쪽

	/**
	 * 재고를 늘리는 UPDATE 를 보낸 <b>뒤에</b> 부른다. 커밋 직전, 행 잠금을 쥔 채로 gen 을 올린다.
	 *
	 * 실패해도 트랜잭션을 실패시키지 않는다 (D-080). 롤백하면 재고 복구 · 교체 · 환불 완료가 Redis 에 묶인다.
	 * 대신 두 번에 나눠 다시 올린다.
	 * <ol>
	 *   <li>커밋 직전에 곧바로 한 번 더. 잠금을 쥔 채라 틈이 생기지 않는다. 잠금은 실패할 때만 최대 한 번의 타임아웃만큼 더 쥔다</li>
	 *   <li>그래도 실패면 커밋 뒤 백그라운드에서 {@link #RETRY_INTERVAL} 간격으로, 커밋 뒤 TTL + 1초까지.
	 *       요청 스레드는 기다리지 않는다</li>
	 * </ol>
	 * 모두 실패하면 남은 틈은 TTL 이 상한이다.
	 * 롤백되면 올리지 않는다. 커밋 직전에 올렸는데 커밋이 실패하면 gen 만 하나 더 올라간다 — DB 에 한 번 더 갈 뿐이다.
	 * 타임아웃으로 실패한 INCR 이 실제로는 적용됐을 수 있다. 그래서 두 번 오를 수 있는데, 앞서가는 쪽이라 해가 없다.
	 */
	public void bumpBeforeCommit(Long saleFormId) {
		if (!enabled) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			if (!incrTwice(saleFormId)) {
				retryLater(saleFormId);
			}
			return;
		}
		@SuppressWarnings("unchecked")
		Set<Long> pending = (Set<Long>) TransactionSynchronizationManager.getResource(this);
		if (pending == null) {
			Set<Long> forms = new LinkedHashSet<>();
			Set<Long> failed = new LinkedHashSet<>();
			TransactionSynchronizationManager.bindResource(this, forms);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void beforeCommit(boolean readOnly) {
					// 엔티티 변경(셀러 재고 수정)은 커밋 때 flush 된다. 그보다 먼저 올리면 "UPDATE 보다 먼저 올린"
					// 꼴이 된다. 여기서 flush 해 UPDATE 를 보내고 잠금을 잡은 뒤에 올린다
					if (!readOnly && entityManager.isJoinedToTransaction()) {
						entityManager.flush();
					}
					for (Long formId : forms) {
						if (!incrTwice(formId)) {
							failed.add(formId);
						}
					}
				}

				@Override
				public void afterCommit() {
					// 재고 복구가 확정된 뒤에만 다시 올린다. 커밋이 실패했으면 복구도 없었으니 올릴 이유가 없다
					failed.forEach(SoldOutCache.this::retryLater);
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(SoldOutCache.this);
				}
			});
			pending = forms;
		}
		pending.add(saleFormId);
	}

	/** 커밋 직전 단계. 실패하면 곧바로 한 번 더 */
	private boolean incrTwice(Long saleFormId) {
		if (incr(saleFormId)) {
			return true;
		}
		if (incr(saleFormId)) {
			log.info("품절 gen 올리기 두 번째에 성공: saleFormId={}", saleFormId);
			return true;
		}
		log.warn("품절 gen 올리기 두 번 실패 — 커밋 뒤 다시 시도한다: saleFormId={}", saleFormId);
		return false;
	}

	/**
	 * 커밋 뒤 단계. TTL + 1초까지만 시도한다 — gen 을 못 올려 생기는 틀린 409 는 "복구 전에 확인한 품절 표시" 가
	 * 살아 있는 동안뿐이고, 그런 표시는 늦어도 커밋 직후에 세워져 TTL 안에 사라진다. 1초는 표시가 늦게 도착할 여유다.
	 * 그 뒤로는 못 올려도 해가 없다.
	 */
	private void retryLater(Long saleFormId) {
		long deadline = System.nanoTime() + ttl.plusSeconds(1).toNanos();
		scheduleRetry(saleFormId, deadline, 1, Duration.ZERO);
	}

	private void scheduleRetry(Long saleFormId, long deadline, int attempt, Duration delay) {
		try {
			retrier.schedule(() -> {
				if (incr(saleFormId)) {
					log.info("품절 gen 올리기 커밋 뒤 {}번째 시도에 성공: saleFormId={}", attempt, saleFormId);
					return;
				}
				if (System.nanoTime() + RETRY_INTERVAL.toNanos() > deadline) {
					log.warn("품절 gen 올리기 포기({}번 시도) — 남은 품절 표시는 TTL 안에 사라진다: saleFormId={}",
							attempt, saleFormId);
					return;
				}
				scheduleRetry(saleFormId, deadline, attempt + 1, RETRY_INTERVAL);
			}, delay.toMillis(), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			// 종료 중이다. 남은 틈은 TTL 이 상한이다
			log.warn("품절 gen 다시 올리기 예약 실패(종료 중): saleFormId={}", saleFormId);
		}
	}

	private boolean incr(Long saleFormId) {
		try {
			incrementer.apply(GEN_FORM + saleFormId);
			return true;
		} catch (RuntimeException e) {
			log.warn("품절 gen 올리기 실패: saleFormId={}, {}: {}",
					saleFormId, e.getClass().getSimpleName(), e.getMessage());
			return false;
		}
	}

	@Override
	public void destroy() {
		retrier.shutdownNow();
		connectionFactory.destroy();
	}

	/** 테스트용 */
	Collection<Long> knownOptions() {
		return formOfOption.keySet();
	}

	/** 테스트용 — Redis 실패를 흉내 낸다. null 이면 원래대로 */
	void incrementer(Function<String, Long> incrementer) {
		this.incrementer = incrementer != null ? incrementer : defaultIncrementer();
	}

	private Function<String, Long> defaultIncrementer() {
		return key -> redis.opsForValue().increment(key);
	}
}
