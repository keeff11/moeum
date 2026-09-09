# point3 API 명세 요약

공식 문서: https://dashboard.point3.io/app/docs/introduction

이 문서는 연동에 필요한 부분만 추린 것이다. 애매하면 원문을 확인할 것.

---

## 1. 엔드포인트

| 용도 | 주소 |
|---|---|
| 본인인증 화면 | `https://auth.point3.io/` |
| 결제 화면 | `https://widget.point3.io` |
| JS SDK | `https://widget.point3.io/v2/standard` |
| 세션 생성 | `POST https://api.point3.io/payment/v3/session` |
| 세션 조회 | `GET https://api.point3.io/payment/v3/session/{sessionId}` |
| 결제 승인 | `POST https://api.point3.io/capture/v2/{sessionId}` |
| 취소 상태 조회 | `GET https://api.point3.io/refunds/v1/{sessionId}` |
| 결제 취소 | `POST https://api.point3.io/refunds/v1/{sessionId}` |
| 취소 재개 | `POST https://api.point3.io/refunds/v1/{sessionId}/resume` |

인증: `Authorization: Bearer <token>`

**API 토큰은 서버 비밀 저장소에만 보관한다.** 브라우저 코드 · URL · 로그 · 공개 저장소에 넣지 않는다.
브라우저에서 쓰는 `client_id`는 이와 완전히 별개의 값이다.

---

## 2. 결제 세션 생성

```
POST /payment/v3/session
Authorization: Bearer <token>

{
  "amount": 32000,
  "productName": "아크릴 스탠드",
  "displayMerchantName": "민지의 작업실",   // 선택, 미지정 시 productName
  "taxFreeAmount": 0,                      // 선택
  "vat": 2909,                             // 선택, 생략 시 자동 계산
  "tradeOpt": "GENERAL"                    // GENERAL | BOOK_CULTURE | PUBLIC_TRANSPORT
}
```

응답:

```json
{
  "id": "pymt_sess-019f0000-0000-7000-9000-000000000000",
  "status": "created",
  "amount": 32000,
  "supplyAmount": 29091,
  "vat": 2909,
  "taxFreeAmount": 0,
  "currency": "KRW",
  "createdAt": "...",
  "updatedAt": "..."
}
```

**응답의 `id`가 `sessionId`다.** 이후 모든 API의 경로 파라미터로 쓰인다.

### 세금 계산 규칙

불변식: `amount = supplyAmount + taxFreeAmount + vat`

`vat` 생략 시 서버 계산:

```
taxableGrossAmount = amount - taxFreeAmount
vat = Math.round(taxableGrossAmount / 11)
supplyAmount = amount - taxFreeAmount - vat
```

`vat`를 직접 전달하면 자동 계산으로 덮어쓰지 않고 검증 통과한 입력값을 그대로 사용한다.
전액 면세는 `taxFreeAmount`를 `amount`와 같게 하고 `vat`는 생략하거나 0.

### 세션 생성 요청에 없는 것

**가맹점 주문번호를 넣을 필드가 없다.** 주문번호 ↔ `sessionId` 매핑은 100% 가맹점 DB 책임.
저장에 실패하면 결제는 진행되는데 어느 주문인지 알 수 없게 된다.

---

## 3. 세션 상태 머신

```
created → identified → initiated → committed → processing → captured
                                                          → failed
```

| 상태 | 의미 | 서버 행동 |
|---|---|---|
| `created` | 세션 생성됨 | 대기 |
| `identified` | 본인인증 완료 | 대기 |
| `initiated` | 결제 진행 중 | 대기 |
| `committed` | **구매자 확정. 아직 출금 전** | 승인 API 호출 |
| `processing` | 승인 처리 중 | 대기 후 재조회 |
| `captured` | 출금 완료 | 확정 처리 |
| `failed` | 실패 확정 | 실패 처리 |

**`committed`는 "돈이 빠져나갔다"가 아니라 "이제 돈을 빼도 된다"이다.**

---

## 4. 결제 승인

```
POST /capture/v2/{sessionId}
Authorization: Bearer <token>
```

응답 `status`: `captured` | `expired` | `failed`

### 핵심 제약

- **멱등하다.** 네트워크 오류 · 타임아웃 · 5xx 뒤에는 시간을 두고 같은 API를 다시 호출하거나 세션 조회로 확인한다
- **`payerId`를 전달하지 않는다.** 승인 요청 바디에 넣지 않는다
- **승인 마감: 결제가 커밋된 날의 다음 날 00:00 (KST)**
  이 시각이 지나면 승인할 수 없다. 미확정 건은 그 전에 반드시 확정해야 한다
- `status: expired`면 세션을 처음부터 다시 시작해야 한다

### HTTP 응답별 처리

| 응답 | 승인됐을 수 있나 | 처리 |
|---|---|---|
| 200 `captured` | 확정 성공 | 주문 완료 |
| 200 `failed` / `expired` | 확정 실패 | 홀드 해제 |
| 200 `processing` | 모름 | 재조회 |
| 400 | 아니오 | 요청 형식 수정 후 실패 처리 |
| 401 / 403 | 아니오 | 자격증명 확인 |
| 404 | 아니오 | 존재하지 않는 세션 |
| **5xx** | **가능** | 되돌리지 말고 재조회 |
| **타임아웃 / 네트워크 오류** | **가능** | 되돌리지 말고 재조회 |

---

## 5. payerId

- 결제를 진행한 사용자의 식별값
- **승인 API에는 전달하지 않는다**
- 가맹점 사용자와 연결해 두면 다음 결제 생성 시 전달해 일부 인증 과정을 생략할 수 있다
- point3가 관리하는 값이므로 **받은 문자열 그대로 저장**한다. 접두사를 떼거나 대소문자를 바꾸면 인증 생략이 동작하지 않는다
- 기존 값과 다르다는 이유로 자동 덮어쓰기 금지
- 형식 예시: `payer:` + 26자 문자열 (파싱해서 쓰지 말 것)

---

## 6. 결제창 연동 — JavaScript SDK

### 설치

```html
<script src="https://widget.point3.io/v2/standard"></script>
```

또는 npm:

```js
// @tosspayments/tosspayments-sdk@2.7.1 을 정확한 버전으로 설치
loadTossPayments(clientKey, {
  src: 'https://widget.point3.io/v2/standard?loader=toss'   // loader=toss 생략 금지
});
```

### 호출 순서

```js
const payments = Point3Payments(clientKey);
const widgets  = payments.widgets({ customerKey: payerId ?? 'ANONYMOUS' });
await widgets.setAmount({ currency: 'KRW', value: 1 });
await widgets.requestPayment({ orderId: sessionId, orderName, successUrl, failUrl });
```

### 이름과 실제가 다른 파라미터 (토스 SDK 호환 때문)

| 파라미터 | 실제로 넣어야 하는 값 |
|---|---|
| `customerKey` | point3 `payerId`. 없으면 `'ANONYMOUS'` |
| `setAmount({ value })` | **호환용. `1` 고정.** 실제 금액은 서버 세션이 결정 |
| `orderId` | 가맹점 주문번호가 아니라 **`sessionId`** |
| `orderName` | 세션의 상품명과 일치하는 100자 이하 문자열 |

### successUrl 쿼리

`orderId`, `payerId`, `amount`

- 별도의 `sessionId` 파라미터는 없다
- **`amount`는 금액 검증 기준으로 쓰지 않는다.** 서버에 저장한 세션 금액을 쓴다
- 가맹점이 미리 붙여둔 쿼리 파라미터는 유지된다

### failUrl 쿼리

`code`, `message`

결과 처리는 `message`가 아니라 `code` 기준:

| code | 의미 |
|---|---|
| `PAYMENT_WINDOW_CLOSED` | 사용자가 결제창을 닫음 |
| `PAYER_DEACTIVATED` | 계정 문제 |
| `SESSION_EXPIRED` | 세션 만료 |
| `INVALID_SESSION` | 잘못된 세션 |

**failUrl에서는 승인 API를 호출하지 않는다.**

### failUrl로 가지 않는 SDK 오류

오류 객체의 `code`로 확인: `INVALID_CLIENT_KEY`, `INVALID_PAYMENT_REQUEST`, `PAYMENT_REQUEST_IN_PROGRESS`

### 기타

- 결제창은 SDK가 자동으로 닫는다. iframe을 직접 제거할 필요 없음
- 로컬 개발에서는 `localhost`, `127.0.0.1`, `[::1]`에 한해 HTTP 결과 URL 허용. 운영은 HTTPS

---

## 7. 결제창 연동 — iframe 직접 (참고)

SDK를 쓰면 필요 없다. 브랜드 컬러나 페이지 상태 유지가 필요할 때만 고려.

### 인증 URL 파라미터

`client_id`(필수), `redirect_uri`(필수, 운영은 `https://widget.point3.io/`), `sessionId`(필수),
`payer_id`(조건부), `keyColor` · `keyLightColor` · `buttonColor`(선택, `#` 제외 6자리 HEX)

> 문서 간 불일치 있음: `authentication-url` 페이지는 base가 `https://auth.point3.io/` 하나이고
> `state` 파라미터가 없다. `web-integration` 페이지는 `/regist` 또는 `/login`으로 시작하고
> `state`가 필수(`p3v1.{uuid}.{base64url(origin,amount)}`)라고 되어 있다.
> 후자가 더 구체적이므로 그쪽 기준으로 구현하되 point3에 확인 필요.

### 메시지 검증 4단계

1. `event.source`가 현재 iframe의 `contentWindow`와 정확히 같은지
2. `event.origin`이 메시지에 맞는 point3 주소와 **정확히 일치**하는지 (접미사 비교 금지)
3. `event.data`와 필드 타입
4. `sessionId`가 현재 세션과 같은지

- `POINT3_CAPTURE_READY`는 `https://widget.point3.io`에서만 수신
- `POINT3_PAYMENT_CLOSE`는 `auth.point3.io` 또는 `widget.point3.io` 양쪽에서 올 수 있음
- `targetOrigin`에 `*` 금지
- 결제 시도마다 `POINT3_CAPTURE_READY`를 한 번만 처리

### CSP

```
frame-src https://auth.point3.io https://widget.point3.io;
```

`referrerpolicy="no-referrer"`는 사용하지 않는다. 기본 정책 또는 `strict-origin-when-cross-origin`.

---

## 8. 결제 취소

```
POST /refunds/v1/{sessionId}

{
  "refundAmount": 32000,
  "refundTaxFreeAmount": 0,
  "refundVat": 2909,
  "reason": "구매자 요청"
}
```

### 핵심 제약

- **네 필드 모두 필수.** 결제 API와 달리 **세금을 자동 계산하지 않는다**
- `refundTaxFreeAmount + refundVat <= refundAmount`
- `reason`은 200자 이내
- `Idempotency-Key`(선택, 300자)는 **중복 차단용이지 재시도용이 아니다.**
  같은 키를 다시 보내면 기존 응답을 재생하지 않고 `409 REFUND_DUPLICATE_REQUEST`를 반환한다.
  결과가 불확실할 때 새 키로 바꾸면 같은 취소가 다시 실행될 수 있다.
  **불확실하면 무조건 상태 조회 먼저.**

### 취소 불가 조건

- 기존 취소가 `PROCESSING`인 경우
- 취소 가능 상태가 아니거나 잔액이 없는 경우
- 취소 총액 · 면세액 · 부가세가 각 잔액을 초과
- 이미 정산됐거나 정산 처리 중
- **매일 23:30 이상 00:30 미만 (KST) — EOB 차단 시간대**

### 409 코드별 처리

| 코드 | 처리 |
|---|---|
| `REFUND_TEMPORARY_UNAVAILABLE` | 미확정 → 조회 후 재개 |
| `REFUND_DUPLICATE_REQUEST` | 조회로 확인 |
| `REFUND_ACTIVE_REQUEST_EXISTS` | 기존 처리 재개 |
| `REFUND_NOT_IN_REFUNDABLE_STATE` | 종료 |
| `SETTLEMENT_DEADLINE_EXCEEDED` | 고객센터 경로 (셀러 직접 환불) |
| `EOB_WINDOW_BLOCKED` | 00:30 이후 재시도 |

### 취소 세션 상태

| 값 | 의미 |
|---|---|
| `refundable` | **처리 중이거나 완료된 취소가 없는** 일반 상태. "취소 가능" 이 아니라 "이력 없음" 이다 |
| `processing` | 취소가 모두 완료되지 않은 상태 |
| `partiallyRefunded` | 요청된 부분 취소가 완료됨 |
| `fullyRefunded` | 원 결제 금액이 모두 취소됨 |

개별 항목: `refunds[].status` = `completed` / `failed` / `processing`

⚠️ **`status == refundable` 로 취소 가능 여부를 판단하지 말 것.** 부분 취소가 한 번이라도
완료되면 `partiallyRefunded` 로 바뀌므로, `refundable` 만 보면 추가 취소를 막게 된다.
판단은 아래 두 필드로 한다.

### 조회 응답 (`PaymentRefundStatusResponse`)

| 필드 | 용도 |
|---|---|
| **`canCreateRefund`** boolean | **새 취소 요청 가능 여부** — 판단의 핵심 |
| **`refundableAmount`** integer | 현재 추가로 취소할 수 있는 금액 — 부분 취소 상한 |
| `originalAmount` | 원 결제 총액 — 세금 안분의 분모 |
| `refunds[]` | 개별 취소 이력. `id` · `status` · `amount` · `vat` · `fee` · **`failure`** |

`refunds[].failure` 에 실패 코드가 담긴다 (예: `refundMethodDeclined`).

**404 는 오류가 아닐 수 있다.** "취소 정보를 찾을 수 없음" 이라 취소한 적 없는 결제를
조회하면 404 가 올 가능성이 있다. 그 경우 "취소 이력 없음 = 전액 취소 가능" 으로 다뤄야 한다.
없는 세션과 구분이 안 되면 우리 DB 에 sessionId 가 있는지로 가른다.
**토큰 발급 후 실제 응답으로 확인할 것.**

### 취소 응답 (`PaymentRefundResponse`)

**`status` 의 enum 값은 `completed` 하나뿐이다.** POST 가 200 을 주면 그 취소는 끝난 것이고,
`processing` 인 채로 200 이 오지 않는다. 외부 결과가 미확정이면 200 이 아니라
`409 REFUND_TEMPORARY_UNAVAILABLE` 로 즉시 알려준다 — 매달아두지 않는다.

**`fee` — 취소 처리 수수료가 붙는다.** 예시에 5,000원 취소에 `fee: 100`.
부분 취소를 여러 번 하면 수수료도 여러 번 붙는다. 셀러 정산에 영향이 있으므로 저장한다.

### 결과 처리 (원문 표)

| 응답 | 처리 |
|---|---|
| 200 | `paymentSessionId` 와 요청값 확인 → 항목 id · 금액 · 세금 · 시각 저장 → 완료 처리 |
| 409 | `result.code` 로 미확정 · 중복 · 정책상 불가를 구분 |
| **422** | **명시적 거절.** 오류 코드와 메시지 기록 |
| 400 · 401 · 404 | 연동 오류 |
| 500 · 503 · 네트워크 · 응답 유실 | **성공·실패를 추정하지 말고 상태 조회** |

### 409 여섯 개는 두 부류다

이 구분이 예외 계층의 기준이다. 4단계에서 4xx/5xx 를 나눈 것과 같은 논리가 409 안에서 반복된다.

**확정 거절 — 취소가 일어나지 않았다. 되돌려도 안전**
`REFUND_NOT_IN_REFUNDABLE_STATE` · `SETTLEMENT_DEADLINE_EXCEEDED` · `EOB_WINDOW_BLOCKED`

**모름 — 조회로 확인해야 한다. 새 취소를 만들면 이중 환불**
`REFUND_TEMPORARY_UNAVAILABLE` · `REFUND_DUPLICATE_REQUEST` · `REFUND_ACTIVE_REQUEST_EXISTS`

`REFUND_TEMPORARY_UNAVAILABLE` 은 point3 판 `CAPTURE_PENDING` 이다 —
point3 도 카드사 결과를 몰라 확답을 못 주는 상태다. 실패가 아니다.
처리 방법에 "**응답 식별값을 보존**" 이 있어, 미확정 409 에도 환불 항목 id 가 실려 올 것으로 읽힌다.
그러면 조회 시 `refunds[]` 에서 우리 건을 집어낼 수 있다. **실제 응답으로 확인할 것.**

### resume

```
POST /refunds/v1/{sessionId}/resume
```

`processing` 인 활성 취소를 이어서 끝낸다. **새 취소를 만들지 않으므로 이중 환불이 없다** —
취소에서 유일하게 안전한 재시도 수단이고, 취소 대사 배치의 주 수단이다.

조회 후 resume 호출 전에 이미 완료됐어도 사고가 없다. 재개하지 않고 최신 상태를 200 으로 반환한다.
즉 **여러 번 불러도 안전하다.**

`processing` 이 생기는 경로는 둘뿐이다.
① `409 REFUND_TEMPORARY_UNAVAILABLE` ② 우리가 응답을 놓침(타임아웃 · 서버 다운).
POST 가 정상 200 을 준 경로에서는 생기지 않는다.

### 테스트 환경이 없다

문서 어디에도 샌드박스 · 테스트 키 언급이 없다 (`introduction` · `operations` ·
`troubleshooting` · `api-reference` 전부 확인). **최종 검증은 소액 실결제로 해야 한다.**

### ✅ 100원 실결제 검증 완료 (2026-09-09)

서버 토큰(연동키)을 받아 운영에서 **결제 → 취소를 한 바퀴 돌렸다. 전부 통과했다.**

| | |
|---|---|
| 세션 생성 | `pymt_sess-` + UUID v7 로 발급됨 → **연동키 유효** |
| 승인 | `POST /orders/{token}/confirm` → `captured` → `PAID` |
| 취소 | 100원 전액, `refund` 1행 `COMPLETED` |
| 재취소 | 차단됨 ("취소할 수 있는 주문이 없습니다") |

**운영 IP 등록은 필요 없었다.** 필요했다면 세션 생성부터 4xx 로 끊겼다.
9절의 미결 항목을 이걸로 닫는다.

**확인된 것 하나 — 승인을 부르지 않으면 결제창이 영원히 돈다.** point3 는 웹훅을
주지 않으므로 `successUrl` 도달(또는 `POINT3_CAPTURE_READY`)만으로는 아무 일도
일어나지 않는다. 검증 중 승인 호출 전까지 결제창이 2분 넘게 로딩만 했다.
프론트 SDK 연동에서 이 호출이 빠지면 그대로 재현된다.

**iframe 직접 연동을 시도했다면(7절)** 최상위 창에서 열면 안 된다. point3 가
결제를 시작한 출처를 부모 창에서 읽기 때문에, 등록된 출처의 iframe 이 아니면
"결제를 시작한 주소를 확인할 수 없습니다" 로 막힌다. SDK 를 쓰면 해당 없다.

---

## 9. 보안

- API 토큰: 서버 비밀 저장소에만. 브라우저 · URL · iframe 메시지 · 로그 · 메신저 · 공개 저장소 금지
- 로그에 남기지 않을 것: 토큰, PIN, 계좌정보, 인증번호, 전체 `payerId`
- 운영 서버 IP 등록 — **필요 여부가 불확실하다.** point3 가이드(`/app/docs/operations`)의
  배포 전 체크리스트와 문제 해결 표에는 "허용 IP" 가 나오지만, `api-reference` 어디에도
  IP 얘기가 없다. 담당자 쪽에서는 필요 없다고 들었다 (2026-09-08).
  토큰을 받는 시점에 확정한다 — 우리 운영 IP 는 `3.37.97.82` (EIP, 고정)

---

## 10. 식별자 형식

전부 랜덤이다. **파싱해서 의미를 꺼내지 말 것.**

| | 형식 |
|---|---|
| `sessionId` | `pymt_sess-` + UUID v7 |
| `clientId` | `client-` + UUID |
| `payerId` | `payer:` + 26자 |
| 환불 항목 `id` | `ref-` + UUID |

`sessionId`에 서명이 없으므로 값 자체를 검증할 수 없다.
진위 판별 방법은 두 가지뿐: ① 내 DB에 저장한 값과 문자열 비교 ② point3에 조회

---

## 11. 문서 전체를 관통하는 원칙

> `POINT3_CAPTURE_READY`와 `successUrl` 도달은 **승인을 요청해도 된다는 신호**일 뿐이다.
> 최종 결제 결과는 가맹점 서버가 승인 API 또는 세션 조회 API의 응답을 확인한 뒤 결정한다.
> 결과를 확인할 수 없다면 같은 주문을 다시 결제하거나 상품을 지급하지 말고 상태를 먼저 확인한다.
