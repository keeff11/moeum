# moeum-api 명세

프론트 팀원이 작성한 「구매자 플로우 화면별 API」를 기준으로 하고,
서버 관점에서 누락·정정이 필요한 부분을 반영했다.

**표시 규칙**
- 🆕 서버가 추가 제안한 항목
- ⚠️ 원본에서 정정이 필요한 항목

---

## 0. 호출 구조

```
브라우저(Client Component) ──→ moeum-api    직접 · CORS + 세션 인증
apps/web Server Component  ──→ moeum-api    렌더 시 직접 · 서버 간 통신
```

BFF 계층은 두지 않는다. CORS·SameSite는 브라우저 발 호출(CSR)에만 적용된다.

**인증: 세션 쿠키 (httpOnly).**
카카오 access/refresh 토큰은 브라우저에 내려보내지 않고 서버가 보관한다.

---

## 1. 엔드포인트 목록

| # | 엔드포인트 | 용도 | 소비 화면 | 인증 |
|---|---|---|---|---|
| 1 | `GET /products/{id}` | 상품 정적 정보 | B1 · B1-O · B2 · B5 | 불필요 |
| 2 | `GET /products/{id}/availability` | 모집 현황 · 재고 (휘발성) | B1 · B2 | 불필요 |
| 3 | `GET /me` | 로그인 사용자 프로필 | B4 · B5 | 필요 |
| 4 | `GET·PUT /me/address` | 배송지 조회 · 등록 | B4 · B5 | 필요 |
| 5 | `POST /checkout-sessions` | 결제 세션 생성 + **재고 홀드** | **B2 옵션·수량 확정** | 필요 |
| 6 | `POST /checkout-sessions/{id}/release` | 홀드 해제 (멱등) | B5 이탈 | 필요 |
| 7 | `POST /checkout-sessions/{id}/pay` | 주문 확정 + PG 결제 준비 | B5 결제하기 | 필요 |
| **8** 🆕 | `POST /orders/{orderToken}/confirm` | **승인 확정** | 복귀 페이지 | 필요 + 소유권 |
| 9 | `GET /orders/{orderToken}` | 결제 결과 조회 (읽기 전용) | 복귀 페이지 | 필요 + 소유권 |
| **10** 🆕 | `GET /orders/{orderToken}/second-payment` | 2차금 청구 내역 | B9 | 필요 + 소유권 |
| **11** 🆕 | `POST /orders/{orderToken}/second-payment` | 2차금 PG 준비 | B10 | 필요 + 소유권 |
| 12 | `GET /auth/kakao/login` | state 발급 + 카카오 인가 URL로 302 | B3 진입 | 불필요 |
| 13 | `GET /auth/kakao/callback` | state 검증 → 토큰 교환 → 세션 발급 | B3 콜백 | 불필요 |
| 14 | `POST /auth/logout` | 세션 폐기 | 어디서든 | 필요 |

> 🆕 **#8이 없으면 결제가 완료되지 않는다.**
> point3는 웹훅을 주지 않는다. 가맹점 서버가 `POST /capture/v2/{sessionId}`를 **직접 호출**해야 출금이 일어난다.
> 원본의 "CONFIRMING(웹훅 미도달)" 표현은 정정이 필요하다. ⚠️

---

## 2. B1 / B1-O · 상품 페이지 (SSR)

### `GET /products/{id}` — 정적 정보 (Data Cache + revalidateTag)

| 필드 | 화면 표시 | 비고 |
|---|---|---|
| `id` | — | 라우트 파라미터 |
| `saleType` | 배지 2차 공구 / 단독 판매 | `GROUP` \| `SOLO`. B1 ↔ B1-O 분기 키 |
| `title` / `images[]` | 상품명 · 이미지 | |
| `seller.id` / `.name` | 헤더 셀러 이름 | B2·B5에서 재사용 |
| `minOrderAmount` | 10,000원부터 주문 가능 | |
| `shippingStartText` | 8월 20일(월) 순차발송 | 서버가 문자열로 포맷 |
| `recruitDeadline` | D-5 | 서버가 렌더 시점에 계산 |
| `recruitTarget` | 68/100명의 분모 | `GROUP`만 |
| `description` | 상품상세설명 | Lexical JSON, ADR 0001 |
| `status` | 구매하기 활성/비활성 | `SELLING` \| `SOLD_OUT` \| `CLOSED` \| `PAUSED` |
| `options[]` | B1 헤드라인 가격 · B2 목록 | 항상 1개 이상 |

B1 헤드라인 가격은 `options[0].deposit1Amount`. 상품 자체의 `price` 필드는 없다.

### `GET /products/{id}/availability` — 휘발성 (no-store)

| 필드 | 화면 표시 | 비고 |
|---|---|---|
| `recruitedCount` | 모집 68/100명의 분자 | **확정 주문 기준 — hold 미포함** |
| `stock` | 재고 5개 · 스티퍼 상한 | `stockMax - held - sold` |
| `status` | 구매하기 활성/비활성 | 품절·마감 실시간 반영 |
| `fetchedAt` | — | `initialDataUpdatedAt`으로 전달 |

---

## 3. B2 · 옵션 선택 바텀시트

옵션 **목록**을 위한 별도 조회 API는 없다 — #1 응답의 `options[]` 를 재사용한다.
가격은 옵션마다 **절대값**이다 (기준가 + 추가금 방식이 아님).

⚠️ 다만 **옵션·수량을 확정하는 순간 `POST /checkout-sessions` 를 호출한다** (6번 참고).
이 시점에 재고가 홀드되고 15분 타이머가 시작된다. 실패하면 B4 로 넘어가지 않는다.

| 필드 | 화면 표시 | 비고 |
|---|---|---|
| `maxPerUser` | 1인당 최대 2개 구매 가능 | 스티퍼 상한 = `min(stock, maxPerUser)` |
| `options[].id` | — | 결제 세션 생성에 전달 |
| `options[].name` | 옵션 A | 옵션 1개뿐인 상품은 상품명과 같을 수 있음 |
| `options[].deposit1Amount` | 32,000원 | **1차금 절대값** — B5 청구 전액 |
| `options[].deposit2Amount` | — | 2차금 순수 상품 잔금. 1차금이 전액이면 0 |
| `options[].optionAmount` | — | `deposit1 + deposit2`. 배송비는 포함하지 않는다 |
| `seller.shippingFee` | — | ⚠️ **옵션이 아니라 셀러 필드다.** 주문 묶음당 1회 |
| `seller.freeShippingOver` | — | 이 금액 이상이면 배송비 면제. `null`이면 미적용 |

⚠️ `options[].stock?` — **옵션별 재고는 관리하지 않는다.**
재고는 판매 폼(상품) 단위이며, 옵션은 금액 계산과 발주서 집계용 차원이다. 이 필드는 제거한다.

⚠️ `options[].shippingFee` — **옵션별 배송비도 없다.**
배송비는 **주문 묶음당 1회**이며 셀러가 갖는다(`seller.shipping_fee`). 옵션마다 실어 보내면
옵션 수량만큼 배송비가 붙는 것으로 오해하기 쉽다. 옵션 총액에서도 배송비를 빼고
`optionAmount = deposit1 + deposit2` 로만 내려보낸다.

"1차/2차"는 구매자가 언제 결제하느냐를 가리키지, 셀러가 언제 입력하느냐가 아니다.

---

## 4. B3 · 소셜 간편가입 (CSR)

카카오 로그인은 인가 코드 흐름. **moeum-api가 로그인 시작·콜백·토큰 교환·세션 발급까지 전부 갖는다.**

| 단계 | 처리 |
|---|---|
| 1. `GET /auth/kakao/login` | state 발급(httpOnly 쿠키) + returnTo 저장 → 카카오 인가 URL로 302 |
| 2. 카카오 동의 화면 | 동의하면 code 발급. 취소하면 code 없이 `?error=access_denied` |
| 3. `GET /auth/kakao/callback` | 쿠키의 state 대조(CSRF) → code·토큰 교환 → 프로필 조회 → 계정 조회/생성 → 세션 Set-Cookie |
| 4. 복귀 | 저장해둔 returnTo(상대 경로 검증 후)로 302 |

### 토큰 보관 ⚠️

- **카카오 access/refresh 토큰은 서버에만 보관**한다 (Redis 또는 DB). 브라우저에 내려보내지 않는다
- 우리 서비스 인증과 카카오 인증은 별개다 — 카카오 토큰은 신원 확인용이고,
  이후 API 호출에는 **우리 세션 쿠키**를 쓴다
- 카카오 토큰을 계속 보관할 이유(알림톡 발송, 프로필 갱신)가 없다면 로그인 후 폐기해도 된다

### 응답

| 응답 | 비고 |
|---|---|
| `302 + Set-Cookie` | 세션 발급까지 moeum-api가 전부 처리 후 returnTo로 리다이렉트 |
| `4xx` | code 만료·재사용, redirectUri 불일치 등 |

세션 갱신은 프론트가 관여하지 않는다. `401`을 받으면 로그인(#12)으로 보낸다.

---

## 5. B4 · 배송지 입력 (CSR)

### `GET · PUT /me/address`

| 필드 | 화면 표시 | 비고 |
|---|---|---|
| `recipientName` | 받는 사람 · 이름 | ⚠️ 카카오 닉네임과 별개로 직접 입력 |
| `phone` | 휴대폰 번호 | 형식 검증은 서버가 |
| `postalCode` | 배송 주소 · 검색 | 우편번호 검색은 프론트가 외부 API 직접 호출 |
| `address1` / `address2` | 주소 · 상세주소 | |
| `memo` | 배송 메모 (선택) | 자유 입력 |

**단일 배송지.** 주소록(복수) 아님. 묶음 배송이라 주문당 하나면 충분하다.
1차금 결제 때 한 번 받고 2차금 청구 시 재수집하지 않는다.

---

## 6. B5 · 1차금 결제 (CSR)

> ⚠️ 와이어프레임의 "상품 32,000원 + 배송비 2,500원 = 총 34,500원"은 갱신 필요.
> 배송비가 2차 결제로 이연되어 **B5 청구액은 옵션가(32,000원)뿐**이다.

### `POST /checkout-sessions` — B2 옵션·수량 확정 시 호출, 재고 홀드 시작 ⚠️

⚠️ **호출 시점이 B5 진입이 아니라 B2 옵션·수량 확정 직후로 바뀌었다.**
배송지를 다 입력한 뒤 품절을 통보받는 상황을 피하기 위해서다 (decisions.md D-001).
이 호출이 성공해야 B4 배송지 화면으로 넘어간다.

```
B1 구매하기 → B2 옵션·수량 확정  ←★ 여기서 홀드 (POST /checkout-sessions)
           → B4 배송지 → B5 결제 화면(타이머) → 결제하기(/pay) → point3
```

**타이머는 B4 부터 보여야 한다.** 홀드가 B4 이전에 시작되므로,
B5 에서만 타이머를 띄우면 사용자는 이미 흘러간 시간을 모른다.

```json
// Request
{ "productId": 12, "optionId": 100, "quantity": 1 }

// 201
{
  "sessionId": "cs_01JB...",
  "holdExpiresAt": "2026-09-01T14:15:00+09:00",
  "remainingSeconds": 900,
  "item": { "title": "아크릴 스탠드", "optionName": "옵션 A", "quantity": 1 },
  "totalPrice": 32000,
  "shippingScheduleText": "1차 8/20, 2차 8/25 · 택배",
  "refundPolicyUrl": "..."
}
```

**서버 동작**

- 판매 폼 단위 조건부 UPDATE로 재고 확보. 영향 행 0이면 품절
- 홀드 만료 15분
- 데드락·락 타임아웃은 서버가 3회 자동 재시도

🆕 **`holdExpiresAt` 타이머 UI는 필수다.** 원본에 "UI 없음 · 디자인 추가 필요"로 되어 있으나,
표시하지 않으면 사용자가 만료 사유를 알 수 없다.

### 실패 응답 — 코드별로 재시도 가능 여부가 다르다 🆕

```json
{
  "code": "OUT_OF_STOCK",
  "message": "품절되었습니다.",
  "recruitedCount": 100,
  "stock": 0
}
```

| code | HTTP | 재시도 | 화면 |
|---|---|---|---|
| `OUT_OF_STOCK` | 409 | 불가 | 품절 안내 |
| `SALE_CLOSED` | 409 | 불가 | 마감 안내 |
| `MAX_PER_USER_EXCEEDED` | 409 | 불가 | 수량 조정 |
| `ACTIVE_SESSION_EXISTS` | 409 | — | 기존 세션으로 이어가기 |
| `TEMPORARY_ERROR` | 503 | **가능** | 일시 오류 + 재시도 버튼 |

마지막을 나머지와 같은 메시지로 묶으면, 재시도하면 성공할 사용자가 포기한다.

### `POST /checkout-sessions/{id}/release` — 이탈 시 해제 (멱등)

`pagehide` + `sendBeacon`으로 호출. 쿠키 인증 전제.

⚠️ **신뢰할 수 없는 경로다.** 브라우저 강제 종료·앱 전환 시 전송되지 않는다.
반드시 서버에 **만료 배치**가 있어야 하며, `/release`는 재고 회전을 앞당기는 최적화일 뿐이다.

⚠️ `sendBeacon`은 커스텀 헤더를 못 싣는다. CSRF 방어와 충돌하지 않도록
이 엔드포인트는 Origin 헤더 검증만으로 처리하거나 토큰을 body에 싣는다.

### `POST /checkout-sessions/{id}/pay` — 결제하기

```json
// 200
{
  "orderId": 88,
  "orderToken": "ord_01JB...",
  "pgParams": {
    "sessionId": "pymt_sess-019f...",
    "clientId": "client-...",
    "orderName": "아크릴 스탠드 옵션 A",
    "successUrl": "https://moeum.com/orders/ord_01JB...?p3=ok",
    "failUrl":    "https://moeum.com/orders/ord_01JB...?p3=fail",
    "payerId": "payer:01JB..."
  }
}
```

**서버 동작**

- 홀드 유효성 재확인 → 만료면 `409 HOLD_EXPIRED`
- 금액은 **서버가 DB에서 계산**한다. 요청에 금액을 받지 않는다
- point3 세션 생성 후 `payment(FIRST)` 저장

프론트는 `pgParams`로 SDK를 호출한다.

```js
const widgets = Point3Payments(clientId).widgets({ customerKey: payerId ?? 'ANONYMOUS' });
await widgets.setAmount({ currency: 'KRW', value: 1 });   // 호환용 고정값
await widgets.requestPayment({ orderId: sessionId, orderName, successUrl, failUrl });
```

---

## 7. 복귀 페이지 (successUrl / failUrl)

### 🆕 `POST /orders/{orderToken}/confirm` — 승인 확정

successUrl 도달 후 **반드시 호출**해야 결제가 완료된다.

```json
// Request
{ "payerId": "payer:01JB..." }

// 200 — 확정 성공
{ "status": "PAID", "paidAmount": 32000 }

// 200 — 확정 실패
{ "status": "FAILED", "failReason": "PAYER_DEACTIVATED" }

// 202 — 결과 불명 (타임아웃 · 5xx)
{ "status": "CONFIRMING" }
```

**서버 동작**

1. 검증: 소유권(`order.userId == session.userId`) · 중복 진입 · `sessionId` 일치 · 홀드 유효성
2. `CAPTURE_PENDING` 커밋 (트랜잭션 종료)
3. **트랜잭션 밖에서** point3 승인 API 호출
4. `captured` → 확정 + 홀드 커밋 + Outbox 적재
5. 4xx → `FAILED` + 홀드 해제
6. **5xx · 타임아웃 → 아무것도 되돌리지 않고 `202 CONFIRMING`**

**멱등하다.** 이미 `PAID`면 그대로 `200 PAID`를 반환한다.

### `GET /orders/{orderToken}` — 조회 (읽기 전용)

인증 후 소유권 대조(`order.userId ≠ session.userId`면 403).

| 필드 | 화면 표시 | 비고 |
|---|---|---|
| `status` | 성공/실패 화면 분기 | 아래 표 참고 |
| `failReason?` | 실패 화면 문구 | 취소/한도/만료 구분 |
| `paidAmount` | 결제 금액 확인 | 서버가 PG 승인 결과와 대조한 값 |
| `item` 요약 | 주문 내용 표시 | #5 응답과 동일 구조 재사용 |

⚠️ **이 API에서 승인을 시도하면 안 된다.** 폴링 대상이므로 부작용이 없어야 한다.

### status 값과 프론트 규약 ⚠️

| status | 의미 | 프론트 |
|---|---|---|
| `PAID` | 확정 성공 | 완료 화면 |
| `FAILED` | 확정 실패 | 실패 화면 + 재시도(새 세션) |
| `CONFIRMING` | **결과 불명 — 확인 중** | 폴링 3초. **결제 버튼 노출 금지** |
| `EXPIRED` | 세션 만료 | 처음부터 다시 |
| `CANCELED` | 취소됨 | 취소 내역 |

**실패 화면은 서버가 `FAILED`라고 명시했을 때만 띄운다.**
네트워크 에러 · 500 · 응답 없음은 전부 `CONFIRMING`으로 취급하고 폴링한다.

프론트는 승인을 재요청하지 않는다. `#8 confirm`은 한 번만, 이후는 `#9`로 조회만 반복한다.

확인 중 화면에 "이 화면을 닫아도 결제는 정상 처리됩니다"를 명시한다.
2분 폴링해도 미확정이면 "확인 지연" 안내 + 주문 내역 보기 / 문의하기.

---

## 8. 2차금 결제 (B9 · B10) 🆕

결제는 1차금(B5) · 2차금(B9/B10) 총 2건이다.
2차 결제 시점에 상품 잔금과 배송비를 **한 번의 PG 트랜잭션으로 함께** 청구한다.

```
2차 결제 청구 총액 = SUM(주문 항목별 deposit2Amount) + 셀러 배송비 1회
```

⚠️ 배송비는 **묶음당 1회**다. 주문 항목 수·수량과 무관하게 한 번만 더한다.
`seller.freeShippingOver` 이상이면 0이 된다.

두 항목을 나누는 이유는 개념(상품 잔금 vs 배송비)이 다르기 때문이고, 청구는 한 번에 합쳐서 이뤄진다.

### `GET /orders/{orderToken}/second-payment`

```json
{
  "amount": 16500,
  "breakdown": { "deposit2Amount": 12000, "shippingFee": 4500 },
  "requestedAt": "2026-09-12T10:00:00+09:00",
  "paid": false,
  "shipping": { ... }
}
```

### `POST /orders/{orderToken}/second-payment`

응답은 `#7 /pay`의 `pgParams`와 **동일한 구조**다.
승인 확정도 `#8 confirm`을 그대로 쓴다(`phase=SECOND`).

**서버는 1차금과 같은 결제 엔진을 재사용한다.** `phase`만 다르다.

**전제 조건:** 주문이 `ARRIVED` 상태여야 한다. 아니면 `409 NOT_ARRIVED`.

> ⚠️ 저장된 `payerId`만으로 서버가 결제를 승인하는 기능은 point3에 없다.
> `payerId`는 인증 단계를 줄일 뿐 결제창 자체는 뜬다.
> 화면 문구를 "승인하기"가 아니라 "16,500원 결제하기"로 할 것.

---

## 9. 서버 내부 규칙 (프론트는 몰라도 됨)

API 표면 뒤에서 서버가 지키는 것들. 자세한 내용은 `docs/payment-flow.md`.

**트랜잭션 경계**

| 구간 | TX |
|---|---|
| 재고 홀드 + 세션 생성 | TX1 |
| point3 세션 생성 API | **TX 밖** |
| `payment` 저장 | TX2 |
| 검증 + `CAPTURE_PENDING` | TX3 |
| point3 승인 API | **TX 밖** |
| 확정 + 홀드 커밋 + Outbox | TX4 |

**배치**

| 배치 | 주기 | 역할 |
|---|---|---|
| 승인 대사 | 30초 | `CAPTURE_PENDING` 조회 후 확정. **없으면 미수금 발생** |
| 홀드 만료 | 1분 | 만료 홀드 회수. `CAPTURE_PENDING` 건은 **제외** |
| Outbox 릴레이 | 1초 | 알림톡 발송 |

**절대 규칙**

- 외부 API를 `@Transactional` 안에 두지 않는다
- 4xx와 5xx를 같은 catch로 잡지 않는다
- 결과가 불확실하면 되돌리지 않는다
- 승인 호출 전에 `CAPTURE_PENDING`을 커밋한다
- 금액은 항상 DB 값을 쓴다
- `captured` 확인 전에 상품을 지급하지 않는다

---

## 10. 원본 「백엔드와 확정할 것」에 대한 답

| # | 항목 | 답 |
|---|---|---|
| ① | 도메인 구성 | 인프라 담당과 함께. `same-site` 서브도메인 권장 |
| ② | status enum 값 목록 | **7번 표 확정.** `PAID / FAILED / CONFIRMING / EXPIRED / CANCELED` |
| ③ | 옵션별 재고 관리 | **안 한다.** 재고는 상품(판매 폼) 단위. `options[].stock` 제거 |
| ④ | 배송지 단일 vs 주소록 | **단일.** 묶음 배송이라 주문당 하나 |
| ⑤ | 날짜·금액 포맷 주체 | **서버가 문자열로 내려준다.** 원시값도 함께 보내 프론트가 선택 가능 |
| ⑥ | B1 헤드라인 가격 표기 | 옵션 간 1차금이 다르면 `~부터` 표기. `options[0].deposit1Amount` 기준 |
| ⑦ | shippingFee 정책 | ⚠️ **셀러 단위 · 주문 묶음당 1회.** 옵션별이 아니다. 2차 결제 시 상품 잔금 합계와 합산 청구 |
| ⑧ | 카카오 앱 등록 정보 | **서버가 갖는다.** `client_id` · `redirect_uri` · `client_secret` 전부 서버 환경변수 |
| ⑨ | 카카오 프로필 활용 범위 | 닉네임만 사용. `recipientName`은 **별도 입력** |
| ⑩ | 세션 갱신 노출 | **노출 안 함.** 서버가 판단해 갱신, 만료면 401. 별도 엔드포인트 불필요 |
| ⑪ | CSRF 심화 방어 | `SameSite=Lax` + **Origin 헤더 검증**. CSRF 토큰까지는 과함 |

---

## 11. 프론트에 요청할 변경 사항

1. 🆕 **`POST /orders/{orderToken}/confirm` 추가** — 없으면 결제가 완료되지 않음
2. ⚠️ **"CONFIRMING(웹훅 미도달)" 표현 정정** — point3는 웹훅이 없고 서버가 직접 승인 호출
3. ⚠️ **`CONFIRMING` 상태의 프론트 규약 명시** — 폴링, 결제 버튼 숨김, 이탈 안내
4. 🆕 **2차금 엔드포인트 2개 추가** — B9 · B10 화면이 있으나 API 없음
5. 🆕 **`holdExpiresAt` 타이머 UI 추가** — 만료 사유를 알 수 없음
6. ⚠️ **`options[].stock` 제거** — 옵션별 재고는 관리하지 않음
7. 🆕 **실패 응답의 `code` 세분화** — 재시도 가능/불가 구분
8. ⚠️ **`/release` 실패 대비 명시** — 서버 만료 배치가 최종 안전망
9. ⚠️ **`options[].shippingFee` · `options[].totalAmount` 제거** — 배송비는 셀러 단위 · 묶음당 1회.
   옵션에는 `optionAmount`(= `deposit1 + deposit2`)만 두고, 배송비는 `seller.shippingFee` 로 받는다
10. ⚠️ **재고 홀드 시점 변경: B5 진입 → B2 옵션·수량 확정 직후.**
    `POST /checkout-sessions` 를 B2 에서 부르고, 성공해야 B4 로 넘어간다.
    **타이머(`holdExpiresAt`)를 B4 배송지 화면부터** 표시해야 한다 — B5 에서만 띄우면
    사용자는 이미 흘러간 시간을 모른 채 만료를 맞는다
