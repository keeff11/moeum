# 도메인

## 1. 개념

| 용어 | 설명 |
|---|---|
| **셀러** | 카카오 로그인 + 심사를 거쳐 판매공간 URL을 받는다 |
| **판매 폼 (sale_form)** | 판매 단위이자 **재고 확보의 주체**. 링크 하나 = 폼 하나 |
| **상품 (product)** | 판매 폼 안의 개별 상품. 폼 하나에 여러 개 |
| **옵션 (product_option)** | 추가금을 가진 선택지. **자체 재고 없음** |
| **주문 묶음 (order_group)** | 결제 1회 · 배송지 1개 · 배송비 1회의 단위 |
| **주문 (orders)** | 판매 폼별 주문. **진행 상태 머신이 여기서 돈다** |
| **1차금** | 상품가. 주문과 동시에 결제 |
| **2차금** | 배송비 또는 잔금. 입고 후 별도 청구 |

### 판매 유형

| | 공동구매 (GROUP) | 단독 (SOLO) |
|---|---|---|
| 목표수량 | 있음 (미달 시 정책 적용) | 없음 |
| 진행 단계 | 모집 → 마감 → 발주 → 제작 → 입고 → 발송 | 결제완료 → 준비중 → 발송 |
| 2차금 | 배송비 또는 잔금 | 없음 (`second_type = NONE`) |
| 재고 성격 | 모집 정원 | 실제 재고 |

---

## 2. 재고

**재고는 판매 폼 단위다.** 상품이나 옵션 단위가 아니다.

```
가용 재고 = stock_max - held - sold
```

- `held` — 결제 확정 전 선점 수량
- `sold` — 결제 확정 수량

### 확보 (조건부 UPDATE)

```sql
UPDATE sale_form
   SET held = held + :qty
 WHERE id = :formId
   AND status = 'SELLING'
   AND stock_max - held - sold >= :qty
   AND (closes_at IS NULL OR closes_at > NOW());
```

영향 행이 0이면 품절 또는 마감. `SELECT` 후 `UPDATE`가 아니라 **한 방으로** 처리한다.

### 확정 / 해제

```sql
-- 확정 (결제 captured 이후)
UPDATE sale_form SET held = held - :qty, sold = sold + :qty WHERE id = :formId;
-- 해제 (실패 · 만료 · 취소)
UPDATE sale_form SET held = held - :qty WHERE id = :formId;
```

### 홀드

- 옵션·수량 확정 시 획득(배송지 입력 전), 만료 15분
- **사용자에게 타이머로 노출한다.** 그래야 만료 화면이 납득 가능해진다
- 실패 지점과 무관하게 같은 규칙 적용. 특별 처리를 만들지 않는다
- 결제 전 검증은 두 곳: 세션 생성 직전(9번), 승인 직전(18번)

---

## 3. 상태 머신

### order_group

```
CREATED → PAY_PENDING → PAID → SECOND_PENDING → SECOND_PAID → SHIPPED
                          ↓                                      
                       CANCELED
   ↓
EXPIRED (홀드 만료)   FAILED (결제 실패)
```

SOLO는 `SECOND_PENDING` · `SECOND_PAID`를 건너뛴다.

### orders (판매 폼별)

```
공동구매:
CREATED → PAID → RECRUITING → CLOSED → PRODUCING → ARRIVED → SHIPPED
                     ↓
                  CANCELED (목표 미달)

단독:
CREATED → PAID → ARRIVED → SHIPPED
```

`RECRUITING` 이후 전이는 **셀러가 대시보드에서 갱신**한다. 자동이 아니다.

### payment

```
CREATED → CAPTURE_PENDING → CAPTURED
                          → FAILED
```

**`CAPTURE_PENDING`은 point3에 없는 우리만의 상태다.** 승인 API 호출 직전에 커밋한다.

### stock_hold

```
HELD → COMMITTED (결제 확정)
     → RELEASED  (실패 · 만료 · 취소)
```

### refund

```
PROCESSING → COMPLETED
           → FAILED
```

---

## 4. 정책

### 확정된 것

| 항목 | 결정 |
|---|---|
| 재고 확보 시점 | 옵션·수량 확정 시, 배송지 입력 전 (A안) |
| 홀드 만료 | 15분 고정, 타이머 노출 |
| 실패 지점별 홀드 특별 처리 | **없음.** 규칙 하나로 통일 |
| 장바구니 범위 | 같은 셀러의 여러 판매 폼 |
| 다른 셀러 상품 | 별도 장바구니 생성 (교체 아님) |
| 장바구니와 재고 | 담을 때 재고를 잡지 않는다 |
| 2차금 청구 시점 | 묶음의 **모든 폼이 입고된 뒤** 일괄 |
| 입고 시점 차이 제한 | **현재 범위에서 제외** |
| 부분 취소 | 폼 단위로 가능 (1:N). 단독은 전액만 |
| 취소 후 배송비 | 남은 폼이 있으면 유지, 전부 취소면 함께 환불 |

### 미확정 — 정해야 할 것

| 항목 | 메모 |
|---|---|
| **타임존** | DB UTC 저장 + 앱에서 KST 변환 vs 전부 KST. point3가 KST 기준이므로 변환 지점을 명확히 |
| **1차금 금액 기준** | 상품가 합계인지, 폼 설정의 고정 금액인지 |
| **2차금 BALANCE(견적형)** | 주문마다 금액이 다른지, 폼 전체 동일한지 |
| **2차금 미납 처리** | 며칠까지 대기 / 독촉 횟수 / 마감 후 자동 취소 여부 |
| **소프트 삭제** | 주문이 있는 판매 폼 · 상품을 지울 수 있는지 |
| **장바구니 가격 변동** | 담아둔 사이 가격이 바뀌면 최신가 표시 + 안내 |
| **정산 완료 판단** | point3가 웹훅을 주는지, 조회해야 하는지, 결제일 기준 추정인지 |
| **목표수량 미달 연장 횟수** | 명세서 F3에 미확정으로 표시됨 |
| **2차금 청구 실행 주체 적법성** | 명세서 F3 1번. point3 답변 대기 중 |

---

## 5. 화면 ↔ 도메인 매핑

### 구매자

| 화면 | 도메인 |
|---|---|
| B0 셀러 페이지 | `seller` + `sale_form` 목록 |
| B1 상품 페이지 | `sale_form` + `product` |
| B2 옵션 선택 | `product_option` |
| B3 카카오 로그인 | `buyer` |
| B5 1차금 결제 + 배송지 | `order_group` 생성 + `payment(FIRST)` + `shipping` |
| B6 결제 예외 | 확인 중 / 실패 / 타임아웃 |
| B7 완료 | |
| B8-1~6 거래 상태 | `orders.status` |
| B9 2차금 청구 도착 | Outbox → 알림톡 |
| B10 2차금 결제 | `payment(SECOND)` |
| B8-C1~C3 취소 | `refund` |
| B13 구매 목록 | `order_group` 목록 |

### 셀러

| 화면 | 도메인 |
|---|---|
| S1~S3 온보딩 | `seller.review_status` |
| S4 판매 목록 | `sale_form` 목록 |
| S5~S6 판매 생성 | `sale_form` + `product` + `product_option` |
| S7 링크 발급 | `sale_form.slug` |
| S8 판매 상세 | 집계 쿼리 |
| S9 상태 변경 | `orders.status` 일괄 전이 + `sale_form_history` |
| S10 2차금 청구 | `order_group.status = SECOND_PENDING` + Outbox |
| S11 미수 추적 | `SECOND_PENDING`인데 `payment(SECOND)`가 `CAPTURED`가 아닌 건 |
| S12 발송 처리 | `shipping.carrier` · `tracking_no` |
| S13~S14 환불 | `refund.settled_manual` |
| ORD 발주서 | `order_item` 옵션별 수량 집계 |

---

## 6. 자주 쓰는 쿼리

### 발주서 — 폼별 · 옵션별 수량 집계

```sql
SELECT i.product_name, i.option_name, SUM(i.qty) AS total_qty
  FROM order_item i
  JOIN orders o ON o.id = i.order_id
 WHERE o.sale_form_id = :formId
   AND o.status NOT IN ('CANCELED','EXPIRED')
 GROUP BY i.product_id, i.option_id;
```

### 2차금 청구 대상 — 모든 폼이 입고된 묶음

```sql
SELECT g.id FROM order_group g
  JOIN orders o ON o.order_group_id = g.id
 WHERE g.status = 'PAID'
 GROUP BY g.id
HAVING SUM(o.status NOT IN ('ARRIVED','CANCELED')) = 0
   AND SUM(o.status = 'ARRIVED') > 0;
```

### 미수 추적

```sql
SELECT g.* FROM order_group g
  LEFT JOIN payment p ON p.order_group_id = g.id
                     AND p.phase = 'SECOND' AND p.status = 'CAPTURED'
 WHERE g.seller_id = :sellerId
   AND g.status = 'SECOND_PENDING'
   AND p.id IS NULL;
```
