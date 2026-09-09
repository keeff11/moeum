package store.moeum.moeum.order.domain;

/**
 * 셀러 주문 목록의 탭 배지 숫자 (와이어프레임 G6).
 *
 * <b>탭마다 COUNT 를 날리지 않는다.</b> 다섯 번 훑을 이유가 없어서 한 번의 집계로 받는다.
 * {@code count(case when ... then 1 end)} 를 쓰는 이유는 {@code sum} 이 한 건도 없을 때
 * 0 이 아니라 null 을 주기 때문이다 — count 는 null 을 세지 않아 그대로 0 이 된다.
 *
 * 탭 필터를 뺀 나머지 조건(검색어 · 판매별 필터)은 그대로 걸린다.
 * 검색 중이면 배지도 그 검색 결과의 숫자여야 화면이 맞는다.
 */
public record SellerOrderCounts(long total, long paymentWaiting, long secondUnpaid,
                                long preparing, long shipped) {
}
