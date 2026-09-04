package store.moeum.moeum.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import store.moeum.moeum.global.auth.SessionUser;
import store.moeum.moeum.order.OrderService;
import store.moeum.moeum.order.dto.OrderCreateRequest;
import store.moeum.moeum.order.exception.OutOfStockException;
import store.moeum.moeum.saleform.domain.Product;
import store.moeum.moeum.saleform.domain.ProductOption;
import store.moeum.moeum.saleform.domain.SaleForm;
import store.moeum.moeum.saleform.domain.SaleFormRepository;
import store.moeum.moeum.saleform.domain.SaleFormStatus;
import store.moeum.moeum.saleform.domain.SaleType;
import store.moeum.moeum.seller.domain.Seller;
import store.moeum.moeum.seller.domain.SellerRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 실험 화면의 뒷단. <b>로컬 프로파일에서만 만들어진다.</b>
 *
 * 인증을 우회하는 통로가 아니다 — 실제 {@link OrderService#place} 를 그대로 호출하고,
 * 매번 일회용 판매 폼을 만들어 쓰고 지운다.
 *
 * NAIVE 모드는 <b>일부러 잘못 짠 코드</b>다. SELECT 로 재고를 읽고 판단한 뒤 UPDATE 하면
 * 왜 초과 판매가 나는지 눈으로 보기 위해서만 존재한다. 운영 경로에는 이런 코드가 없다.
 */
@Slf4j
@Service
@Profile("local")
@RequiredArgsConstructor
public class StockHoldDemoService {

	private static final int MAX_THREADS = 500;
	private static final int POOL_SIZE = 32;

	private final OrderService orderService;
	private final SellerRepository sellerRepository;
	private final SaleFormRepository saleFormRepository;
	private final JdbcTemplate jdbcTemplate;

	public enum Mode {
		/** 운영 코드 그대로 — 조건부 UPDATE 한 방 */
		CONDITIONAL_UPDATE,
		/** 일부러 잘못 짠 코드 — SELECT 로 읽고 판단한 뒤 UPDATE */
		NAIVE_READ_THEN_WRITE
	}

	public DemoResult run(Mode mode, int stockMax, int threads) {
		int safeThreads = Math.min(Math.max(threads, 1), MAX_THREADS);
		int safeStock = Math.max(stockMax, 1);

		Fixture fixture = createFixture(safeStock);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger outOfStock = new AtomicInteger();
		AtomicInteger otherError = new AtomicInteger();

		CountDownLatch ready = new CountDownLatch(safeThreads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(safeThreads);
		ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);

		long startedAt = System.nanoTime();
		try {
			for (int i = 0; i < safeThreads; i++) {
				int index = i;
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						if (mode == Mode.CONDITIONAL_UPDATE) {
							orderService.place(
									new SessionUser("demo-buyer-" + index, "구매자" + index),
									new OrderCreateRequest(List.of(
											new OrderCreateRequest.Item(fixture.optionId(), 1))));
						} else {
							naiveHold(fixture.saleFormId(), 1);
						}
						success.incrementAndGet();
					} catch (OutOfStockException e) {
						outOfStock.incrementAndGet();
					} catch (Exception e) {
						otherError.incrementAndGet();
					} finally {
						done.countDown();
					}
				});
			}

			ready.await(30, TimeUnit.SECONDS);
			start.countDown();                       // 전부 한꺼번에 푼다
			done.await(180, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			pool.shutdownNow();
		}

		long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
		int held = intOf("SELECT held FROM sale_form WHERE id = ?", fixture.saleFormId());
		int sold = intOf("SELECT sold FROM sale_form WHERE id = ?", fixture.saleFormId());

		cleanUp();

		int oversold = Math.max(0, held + sold - safeStock);
		return new DemoResult(
				mode.name(), safeStock, safeThreads,
				success.get(), outOfStock.get(), otherError.get(),
				held, sold, oversold, oversold == 0, elapsedMs,
				notesFor(mode, oversold));
	}

	/**
	 * <b>일부러 잘못 짠 코드.</b> 읽고 → 판단하고 → 쓴다.
	 *
	 * 두 요청이 같은 값(예: 남은 재고 1)을 읽으면 둘 다 "가능"으로 판단하고 각자 더한다.
	 * 사이의 짧은 지연은 실제 서비스 로직이 차지하는 시간을 대신한다 —
	 * 지연이 없어도 경합은 생기지만, 창이 좁아 눈에 덜 보인다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void naiveHold(Long saleFormId, int qty) {
		Integer remaining = jdbcTemplate.queryForObject(
				"SELECT stock_max - held - sold FROM sale_form WHERE id = ?", Integer.class, saleFormId);

		if (remaining == null || remaining < qty) {
			throw new OutOfStockException(saleFormId, "품절");
		}
		try {
			Thread.sleep(2);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// 조건이 없다. 이미 남이 가져갔어도 그냥 더한다
		jdbcTemplate.update("UPDATE sale_form SET held = held + ? WHERE id = ?", qty, saleFormId);
	}

	private List<String> notesFor(Mode mode, int oversold) {
		List<String> notes = new ArrayList<>();
		if (mode == Mode.CONDITIONAL_UPDATE) {
			notes.add("조건부 UPDATE 한 방 — 판정과 갱신이 한 문장에서 원자적으로 끝난다");
			notes.add("영향 행 0이면 품절. 재시도하지 않는다");
		} else {
			notes.add("SELECT 로 읽고 판단한 뒤 UPDATE — 두 요청이 같은 값을 읽으면 각자 더한다");
			notes.add(oversold > 0
					? "초과 판매 " + oversold + "개 발생. 재고보다 많이 팔렸다"
					: "이번에는 경합이 안 걸렸다. 스레드 수를 늘려 다시 실행해 보라");
		}
		return notes;
	}

	@Transactional
	public Fixture createFixture(int stockMax) {
		long unique = System.nanoTime();

		Seller seller = sellerRepository.save(Seller.builder()
				.kakaoId("demo-seller-" + unique)
				.storeSlug("demo-" + unique)
				.shippingFee(3000)
				.build());
		seller.approve();

		SaleForm form = SaleForm.builder()
				.seller(seller)
				.title("동시성 실험용 공구")
				.slug("demo-form-" + unique)
				.saleType(SaleType.GROUP)
				.stockMax(stockMax)
				.targetQty(1)
				.closesAt(LocalDateTime.now().plusDays(1))
				.minOrderAmount(0)
				.build();

		Product product = Product.builder().name("실험 상품").sortOrder(0).build();
		product.addOption(ProductOption.builder()
				.name("옵션 A").deposit1Amount(20000).deposit2Amount(12000).sortOrder(0).build());
		form.addProduct(product);

		saleFormRepository.saveAndFlush(form);
		jdbcTemplate.update("UPDATE sale_form SET status = ? WHERE id = ?",
				SaleFormStatus.SELLING.name(), form.getId());

		return new Fixture(form.getId(), form.getProducts().get(0).getOptions().get(0).getId());
	}

	/** 실험 흔적을 남기지 않는다. FK 순서대로 지운다 */
	@Transactional
	public void cleanUp() {
		jdbcTemplate.update("DELETE FROM stock_hold");
		jdbcTemplate.update("DELETE FROM order_item");
		jdbcTemplate.update("DELETE FROM orders");
		jdbcTemplate.update("DELETE FROM order_group");
		jdbcTemplate.update("DELETE FROM cart_item WHERE sale_form_id IN "
				+ "(SELECT id FROM sale_form WHERE slug LIKE 'demo-form-%')");
		jdbcTemplate.update("DELETE FROM buyer WHERE kakao_id LIKE 'demo-buyer-%'");
		jdbcTemplate.update("DELETE FROM product_option WHERE product_id IN "
				+ "(SELECT id FROM product WHERE sale_form_id IN "
				+ "(SELECT id FROM sale_form WHERE slug LIKE 'demo-form-%'))");
		jdbcTemplate.update("DELETE FROM product WHERE sale_form_id IN "
				+ "(SELECT id FROM sale_form WHERE slug LIKE 'demo-form-%')");
		jdbcTemplate.update("DELETE FROM sale_form WHERE slug LIKE 'demo-form-%'");
		jdbcTemplate.update("DELETE FROM seller WHERE kakao_id LIKE 'demo-seller-%'");
	}

	private int intOf(String sql, Object arg) {
		Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
		return value == null ? 0 : value;
	}

	public record Fixture(Long saleFormId, Long optionId) {
	}
}
