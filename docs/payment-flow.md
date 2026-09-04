# 결제 흐름

## 0. 전체 그림

```
구매자 → 프론트 → 백엔드 → DB
                    ↓
                 point3
```

- **1차금**: 옵션·수량 확정 시 재고 홀드 → (배송지) → 결제 → 승인 → 확정
- **2차금**: 전 폼 입고 후 배송비 청구 → 결제 → 승인 → 확정 (홀드 없음)
- 결제 엔진은 하나. `phase` 파라미터만 다르다

---

## 1. 1차금 흐름

### 실시간 (1~24)

| # | 주체 | 동작 | 트랜잭션 |
|---|---|---|---|
| 1 | 구매자 → 프론트 | B2 옵션·수량 확정 | |
| 2 | 프론트 → 백엔드 | 주문 생성 요청 (장바구니 내용) | |
| 3 | 백엔드 → DB | 폼별 재고 · 모집 확보 (조건부 UPDATE) | **TX1** |
| 4 | DB → 백엔드 | 홀드 생성 (만료 15분) | **TX1** |
| 5 | 백엔드 → 프론트 | `groupNo` · 만료 시각 | |
| 6 | 프론트 → 구매자 | B4 배송지 · B5 결제 화면 + 타이머 | |
| 7 | 구매자 → 프론트 | 결제하기 클릭 | |
| 8 | 프론트 → 백엔드 | 세션 요청 | |
| 9 | 백엔드 → DB | 홀드 유효성 · 공구 마감 확인 | |
| 10 | 백엔드 → point3 | 세션 생성 | **TX 밖** |
| 11 | point3 → 백엔드 | `sessionId` | **TX 밖** |
| 12 | 백엔드 → DB | `payment(FIRST)` 저장 + 묶음 `PAY_PENDING` | **TX2** |
| 13 | 백엔드 → 프론트 | `sessionId` · successUrl · failUrl | |
| 14 | 프론트 → point3 | `requestPayment()` | |
| 15 | 구매자 → point3 | 본인인증 · 결제 확정 | |
| 16 | point3 → 프론트 | successUrl 이동 (`orderId`, `payerId`) | |
| 17 | 프론트 → 백엔드 | 결과값 전달 | |
| 18 | 백엔드 → DB | **검증 4종 + `CAPTURE_PENDING` 기록** | **TX3** |
| 19 | 백엔드 → point3 | 승인 요청 | **TX 밖** |
| 20 | point3 → 백엔드 | `captured` | **TX 밖** |
| 21 | 백엔드 → DB | 묶음 `PAID` + 홀드 확정 | **TX4** |
| 22 | 백엔드 → DB | Outbox 적재 | **TX4** |
| 23 | 백엔드 → 프론트 | 결과 응답 | |
| 24 | 프론트 → 구매자 | 완료 화면 | |

### 18번 검증 4종

```java
Order order = orderRepo.findByIdForUpdate(orderId);      // 비관적 락
if (!order.getBuyerId().equals(userId))     throw Forbidden;
if (group.getStatus() == PAID)              return AlreadyPaid;   // 중복 진입
if (!payment.getSessionId().equals(sid))    throw Invalid;
if (!holdRepo.isValid(orderId))             throw HoldExpired;    // 돈 나가기 전 차단
payment.markCapturePending();
```

**18번이 승인 전 마지막 관문이다.** 여기서 걸러야 취소 API의 시간 제약과 싸우지 않는다.

### 21번 확정 (멱등)

```java
@Transactional
public void finalizeCapture(Long paymentId) {
    Payment p = paymentRepo.findByIdForUpdate(paymentId);
    if (p.getStatus() == CAPTURED) return;       // 멱등 가드 — 없으면 재고 이중 차감

    p.markCaptured();
    group.markPaid();
    stockService.commit(groupId);                // held -= qty, sold += qty
    buyer.savePayerId(payerId);                  // 다음 결제 인증 생략용
    outboxRepo.save(Outbox.of(groupId, "ORDER_PAID", payload));
}
```

**실시간 21번과 대사 배치가 같은 메서드를 호출한다.** 배치는 누락된 21번을 대신 실행하는 것.

---

## 2. 2차금 흐름

1차금과 동일하되 **홀드 관련 단계만 빠진다.**

| # | 동작 | 비고 |
|---|---|---|
| 1~2 | 청구 화면 진입 → 결제 요청 | |
| 3 | 상태 · 중복 확인 | 묶음의 모든 orders가 `ARRIVED`인지, `SECOND`가 이미 `CAPTURED`가 아닌지 |
| 4~5 | 세션 생성 (`payer_id` 전달) | 저장해둔 `payerId`로 인증 단계 축소 |
| 6 | `payment(SECOND)` 저장 | |
| 7~11 | 결제창 → 확정 → 결과 전달 | |
| 12 | 검증 + `CAPTURE_PENDING` | |
| 13~14 | 승인 호출 → `captured` | |
| 15 | 묶음 `SECOND_PAID` + Outbox | 홀드 확정 없음 |

> **주의:** 저장된 `payerId`만으로 서버가 결제를 승인하는 기능은 문서에 없다.
> `payerId`는 인증 단계를 줄일 뿐 결제창 자체를 건너뛰지는 못한다.
> 화면 문구를 "승인하기"가 아니라 "4,000원 결제하기"로 할 것.

---

## 3. 실패 케이스별 처리

승인 API 호출을 기준으로 나눈다. 판단 기준은 **"돈이 나갔을 가능성이 있느냐"**.

### A. 승인 호출 전 (3~17번)

| 상황 | 상태 | 돈 | 처리 |
|---|---|---|---|
| 품절 · 마감 | 롤백 | 안 나감 | 재시도 불가, 품절 안내 |
| DB 오류 (3~4번) | 롤백 | 안 나감 | 남는 것 없음. 재시도 가능 |
| 데드락 · 락 타임아웃 | 롤백 | 안 나감 | `@Retryable` 3회 자동 재시도 |
| 세션 생성 실패 (10~11번) | 홀드 유지 | 안 나감 | 타이머 남은 시간 안에 재시도 |
| 12번 롤백 | 홀드 유지 | 안 나감 | point3 세션은 방치 후 만료. 재시도는 **새 세션** |

세션 생성이 타임아웃이어도 홀드를 풀어도 안전하다. 사용자가 결제창을 못 봤으니 결제가 진행되지 않는다.

### B. `CAPTURE_PENDING` 커밋 후, 승인 호출 전 다운

- 상태: `CAPTURE_PENDING`, 승인은 안 보냄
- 돈: 안 나감
- 처리: 대사 배치가 조회 → `committed`면 **승인을 새로 호출**

```java
case "committed" -> retryCapture(p);   // 조회만 하고 끝내면 안 된다
```

### C. 승인 요청 타임아웃 / 5xx

- 상태: `CAPTURE_PENDING` 유지
- 돈: **모름**
- 처리: **아무것도 되돌리지 않는다.** 홀드도 풀지 않는다. 배치에 위임
- 응답: `202 pending` → 프론트는 확인 중 화면 + 폴링

### D. 승인 요청 4xx

- 상태: `CAPTURE_PENDING` → `FAILED`
- 돈: 안 나감
- 처리: 즉시 실패 처리 + 홀드 해제
- 응답: `200 failed` → 실패 화면 + 재시도 버튼

**C와 D를 반드시 분리한다.**

```java
try {
    var res = point3Client.capture(sessionId);
    switch (res.status()) {
        case "captured"          -> finalizeCapture(paymentId);
        case "failed", "expired" -> fail(paymentId, res.status());
        default                  -> pending(paymentId);   // processing
    }
} catch (HttpClientErrorException e) {                    // 4xx
    fail(paymentId, e.getStatusCode().toString());
} catch (HttpServerErrorException | ResourceAccessException e) {  // 5xx · 타임아웃
    pending(paymentId);
}
```

`RestClientException` 하나로 잡으면 5xx까지 실패 처리되어 **미수금이 생긴다.**

### E. `captured` 받고 확정 트랜잭션 전에 다운

- 상태: `CAPTURE_PENDING`
- 돈: **나감**
- 처리: 대사 배치가 조회 → `captured` 확인 → `finalizeCapture()`

### F. 확정 트랜잭션 실행 중 실패

- 상태: 롤백되어 `CAPTURE_PENDING` 유지
- 돈: 나감
- 처리: 배치가 재시도. **멱등 가드가 결정적**

### G. 확정 커밋 후 응답 전에 다운

- 상태: `CAPTURED`, 묶음 `PAID`
- 처리: 서버는 할 일을 다 했다. 프론트 폴링이 `PAID`를 받음
- 주의: 주문 조회 시 이미 `PAID`면 완료 페이지로 리다이렉트 (재결제 방지)

---

## 4. 프론트 응답 매핑

| 서버 응답 | 화면 |
|---|---|
| `200 paid` | 완료 |
| `200 failed` | 실패 + 재시도 버튼 (새 세션 발급) |
| `202 pending` | **확인 중** + 상태 조회 폴링 |
| 네트워크 에러 / 타임아웃 | **확인 중** + 폴링 |
| `500` | **확인 중** + 폴링 |

### 원칙

1. **실패 화면은 서버가 `failed`라고 명시했을 때만.** 모르는 것은 실패가 아니다
2. **확인 중 화면에는 결제 버튼을 두지 않는다.** 이중 결제의 원인
3. **프론트는 승인을 재요청하지 않는다.** `GET` 상태 조회만 반복
4. 확인 중 화면에 "이 화면을 닫아도 결제는 정상 처리됩니다"를 명시
5. 2분 폴링해도 미확정이면 "확인 지연" 화면 + 주문 내역 보기 / 문의하기

### 폴링

```js
// 승인 요청이 실패해도 승인을 다시 보내지 않는다. 조회만 반복
const timer = setInterval(async () => {
  const { status } = await getStatus(groupNo);
  if (status === 'PAID')   { clearInterval(timer); goSuccess(); }
  if (status === 'FAILED') { clearInterval(timer); goFail(); }
}, 3000);
```

상태 조회 API는 **부작용이 없어야 한다.** 여기서 승인을 시도하면 안 된다.

---

## 5. 배치

| 배치 | 주기 | 대상 | 하는 일 |
|---|---|---|---|
| **승인 대사** | 30초 | `payment.status = CAPTURE_PENDING`, `updated_at` 30초 경과 | point3 조회 후 확정. 승인 마감 임박 건 우선 |
| **홀드 만료** | 1분 | 만료된 `stock_hold`, **`CAPTURE_PENDING` 묶음 제외** | 재고 회수 |
| **Outbox 릴레이** | 1초 | `outbox.status = PENDING` | 알림톡 발송, 실패 시 백오프 |
| **공구 마감** | 1분 | `closes_at` 지난 `SELLING` 폼 | `CLOSED` 전이 + `shortfall_policy` 적용 |
| **취소 대사** | 30초 | `refund.status = PROCESSING` | 취소 상태 조회 후 확정 |
| **2차금 미납** | 1일 | 오래된 `SECOND_PENDING` | 알림 또는 정책 처리 |

모두 `FOR UPDATE SKIP LOCKED`로 인스턴스 간 분산.

### 승인 대사 배치 로직

```java
for (Payment p : paymentRepo.findStalePending()) {
    var res = point3Client.getSession(p.getSessionId());
    switch (res.status()) {
        case "captured"          -> finalizeCapture(p.getId());
        case "failed", "expired" -> fail(p.getId(), res.status());
        case "processing"        -> { }                    // 다음 주기
        case "committed"         -> retryCapture(p);       // B 케이스
        default                  -> { }
    }
}
```

**`committed` 분기를 빼먹으면** B 케이스가 영원히 미확정으로 남고 다음 날 00:00에 승인 기회를 잃는다.

### 홀드 만료 배치 쿼리

```sql
SELECT h.* FROM stock_hold h
  JOIN orders o       ON o.id = h.order_id
  JOIN order_group g  ON g.id = o.order_group_id
  LEFT JOIN payment p ON p.order_group_id = g.id AND p.status = 'CAPTURE_PENDING'
 WHERE h.status = 'HELD'
   AND h.expires_at < NOW(6)
   AND p.id IS NULL          -- 미확정 묶음은 건드리지 않는다
 LIMIT 100 FOR UPDATE SKIP LOCKED;
```

---

## 6. 취소 흐름

1. 정산 완료 여부 확인 (완료 후에는 API 취소 불가 → 셀러 직접 환불 접수)
2. EOB 차단 시간대(23:30~00:30 KST)가 아닌지 확인
3. **취소 금액과 세금 구성을 직접 계산** (자동 계산 안 됨)
4. `POST /refunds/v1/{sessionId}` 호출
5. `200`이면 `refund` 행 저장 + 완료 처리
6. **`409` / 타임아웃 / 응답 유실이면 재요청 금지.** `GET /refunds/v1/{sessionId}`로 조회
7. 활성 항목이 `processing`이면 `resume` API로 재개

### 부분 취소 세금 계산

```java
long refundVat      = Math.round(refundAmount * (double) payment.getVat() / payment.getAmount());
long refundTaxFree  = Math.round(refundAmount * (double) payment.getTaxFreeAmount() / payment.getAmount());
// 잔액 초과 여부 검증 필수
```

### 1차금 / 2차금 취소 조합

- 사용자는 "주문을 취소"하고, 서버가 이를 1~2건의 결제 취소로 번역한다
- 한 폼만 취소되면 그 `orders`만 `CANCELED`, 해당 상품가만 부분 환불
- 배송비는 남은 폼이 있으면 유지, 전부 취소되면 함께 환불
- 두 결제 중 하나만 성공하면 실패 건은 재시도 대상으로 남긴다
