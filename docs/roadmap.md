# 개발 로드맵

한 번에 다 만들지 않는다. 단계마다 동작하는 상태로 마무리하고 다음으로 넘어간다.

---

## 1단계 — 프로젝트 뼈대

**목표:** 앱이 뜨고 DB에 연결되고 스키마가 올라간다.

- [ ] Spring Boot 3.x + Java 17 프로젝트 생성 (Gradle)
- [ ] 의존성: Web, JPA, Validation, MySQL Driver, Flyway, Lombok, Retry
- [ ] `application.yml` 프로파일 분리 (local / prod)
- [ ] Flyway 마이그레이션 `V1__init.sql` — `docs/schema.sql` 내용
- [ ] Docker Compose로 로컬 MySQL
- [ ] 헬스체크 엔드포인트
- [ ] 전역 예외 핸들러 (`@RestControllerAdvice`) + 공통 에러 응답 포맷

**확인:** 앱이 뜨고 테이블이 전부 생성되어 있다.

---

## 2단계 — 셀러 · 판매 폼

**목표:** 결제 없는 CRUD로 JPA 감을 잡는다.

- [ ] 엔티티 매핑 (`Seller`, `SaleForm`, `Product`, `ProductOption`)
- [ ] 카카오 OAuth 로그인 (셀러)
- [ ] 온보딩 정보 제출 → `review_status = PENDING`
- [ ] 심사 승인 처리 (일단 수동 API로)
- [ ] 판매 폼 생성 (상품 · 옵션 포함)
- [ ] 판매 폼 목록 · 상세 조회
- [ ] 판매 폼 수정 시 `sale_form_history` 기록

**주의:** 민감정보(`business_no`, `settlement_account`)는 암호화 컬럼 변환기(`AttributeConverter`)로 처리.

---

## 3단계 — 구매자 · 장바구니 · 주문 생성

**목표:** 동시성 제어가 처음 등장한다. **여기서 테스트를 쓰기 시작한다.**

- [ ] 카카오 OAuth 로그인 (구매자)
- [ ] 장바구니 담기 (같은 셀러 검증)
- [ ] 장바구니 조회 (마감 · 품절 상태 표시)
- [ ] **주문 생성 + 재고 홀드** — 조건부 UPDATE, `sale_form_id` 오름차순 정렬
- [ ] `@Retryable`로 데드락 · 락 타임아웃 재시도
- [ ] 홀드 만료 배치
- [ ] 배송지 저장

**테스트 (필수)**

```java
@Test void 동시에_N명이_주문하면_재고만큼만_성공한다()
@Test void 품절이면_OutOfStockException_이고_재시도하지_않는다()
@Test void 여러_폼을_담으면_하나만_품절이어도_전체_롤백된다()
@Test void 만료된_홀드는_배치가_회수한다()
```

**확인:** 재고 100개에 스레드 200개를 동시에 던져도 `sold + held <= 100`.

---

## 4단계 — 1차금 결제 ★ 가장 중요

**목표:** point3 연동. 실패 케이스 A~G를 전부 처리한다.

- [ ] `Point3Client` — 세션 생성 / 조회 / 승인 (타임아웃 설정 필수)
- [ ] 4xx / 5xx 예외 분리 (`HttpClientErrorException` vs `HttpServerErrorException`)
- [ ] `PaymentService.createSession(groupId, phase)` — 홀드 유효성 확인 후 세션 생성
- [ ] `PaymentService.confirm(...)` — **검증 4종 → `CAPTURE_PENDING` 커밋 → 승인 호출**
- [ ] `finalizeCapture(paymentId)` — 멱등 가드 + 확정 + 홀드 커밋
- [ ] `fail(paymentId, reason)` — 실패 처리 + 홀드 해제
- [ ] 상태 조회 API (부작용 없음)
- [ ] **승인 대사 배치** — `FOR UPDATE SKIP LOCKED`, `committed` 분기 포함
- [ ] `payment_event` 기록
- [ ] 프론트: SDK 연동, 확인 중 화면 + 폴링

**테스트 (필수)**

```java
@Test void 승인_타임아웃이면_홀드를_풀지_않고_CAPTURE_PENDING을_유지한다()
@Test void 승인_4xx면_즉시_실패_처리하고_홀드를_해제한다()
@Test void 승인_5xx면_되돌리지_않는다()
@Test void 배치가_두_번_확정해도_재고가_한_번만_차감된다()
@Test void CAPTURE_PENDING_상태에서_배치가_captured를_확인하면_확정한다()
@Test void CAPTURE_PENDING인데_세션이_committed면_승인을_다시_호출한다()
@Test void 홀드가_만료됐으면_승인을_보내지_않는다()
@Test void 같은_주문에_confirm이_두_번_와도_한_번만_처리된다()
```

WireMock으로 타임아웃 · 5xx · `processing` 응답을 재현한다.

**확인:** 어느 지점에서 서버를 죽여도 배치가 복구한다.

---

## 5단계 — 2차금

**목표:** 4단계 코드를 재사용한다. 새로 짜지 않는다.

- [ ] 셀러 입고 처리 → `orders.status = ARRIVED`
- [ ] 2차금 청구 대상 조회 (모든 폼이 `ARRIVED`인 묶음)
- [ ] `payment(SECOND)` 생성 — `payer_id` 전달
- [ ] 구매자 2차금 결제 화면
- [ ] 미수 추적 조회
- [ ] 2차금 미납 배치

**확인:** `PaymentService`가 `phase`만 다르게 받아 동작한다. 중복 코드가 없다.

---

## 6단계 — 취소 · 환불

- [ ] 취소 가능 여부 판단 (정산 전 / EOB 시간대 / 이미 취소 중)
- [ ] 부분 취소 세금 비율 계산
- [ ] `Point3Client.refund(...)` + 409 코드별 분기
- [ ] `resume` API 연동
- [ ] **취소 대사 배치**
- [ ] 정산 후 환불 접수 (`settled_manual`)
- [ ] 목표수량 미달 시 자동 취소 (`shortfall_policy`)

**테스트**

```java
@Test void 취소_409면_재요청하지_않고_조회한다()
@Test void EOB_시간대에는_취소를_시도하지_않는다()
@Test void 부분_취소_시_세금이_비율대로_계산된다()
@Test void 한_폼만_취소되면_배송비는_유지된다()
```

---

## 7단계 — 알림 · 대시보드

- [ ] Outbox 테이블 + 릴레이 배치
- [ ] 카카오 알림톡 연동 (2차금 청구, 상태 변경)
- [ ] 재시도 상한 + DEAD 처리
- [ ] 셀러 대시보드 집계 API
- [ ] 발주서 다운로드 (옵션별 수량 집계 → CSV/Excel)
- [ ] 송장 등록 (개별 + 일괄 업로드)

---

## 8단계 — 마무리

- [ ] 로그 마스킹 (토큰 · PIN · `payer_id`)
- [ ] 운영 프로파일 설정, 시크릿 분리
- [ ] point3 운영 서버 IP 등록
- [ ] 부하 테스트 (재고 확보 구간)
- [ ] README — 아키텍처 다이어그램, 실행 방법

---

## 진행 원칙

1. **한 단계씩.** 4단계를 건너뛰고 5단계를 하지 않는다
2. **3~4단계는 테스트 없이 넘어가지 않는다.** 동시성과 실패 처리는 손으로 확인할 수 없다
3. 스키마 변경은 새 Flyway 파일로. 기존 파일을 수정하지 않는다
4. `docs/decisions.md`의 미확정 항목이 확정되면 문서를 갱신한다
