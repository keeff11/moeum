package store.moeum.moeum.payment.domain;

/**
 * 주문묶음 하나에 대한 금액 합계. 목록 화면이 묶음 id 로 접어 쓴다.
 *
 * <b>묶음당 한 줄로 돌아오는 집계 결과다.</b> 카드마다 결제·취소 행을 세면
 * 목록 한 장(20건)에 조회가 그만큼 더 나간다 — 한 번에 끌어와 Map 으로 접는다.
 *
 * {@code sum()} 이 long 을 주므로 long 으로 받고 쓰는 쪽에서 int 로 좁힌다.
 * 금액이 int 범위를 넘을 일은 없지만 JPQL 생성자 표현식의 타입을 맞춰야 한다.
 */
public record GroupAmount(Long orderGroupId, long amount) {
}
