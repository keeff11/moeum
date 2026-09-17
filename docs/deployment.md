# 배포

EC2 한 대에 Docker Compose 로 앱·MySQL·Caddy 를 올린다.
`main` 에 푸시하면 GitHub Actions 가 테스트 → 이미지 빌드 → ECR 푸시 → SSM 배포까지 자동으로 한다.

```
git push origin main
      │
      ▼
GitHub Actions ── 테스트(Testcontainers)
      │
      ├─ 이미지 빌드 → ECR 푸시 (태그: 커밋 SHA)
      │
      └─ SSM SendCommand ─────────────► EC2 (t3.small, AL2023)
                                          ├─ Parameter Store 에서 .env 생성
                                          ├─ docker compose pull && up -d
                                          └─ readiness 확인 후 성공 처리

                        Caddy(443) ─► app(8080) ─► mysql(3306)
```

리전은 `ap-northeast-2`(서울) 기준이다. 아래 명령의 `<ACCOUNT_ID>` 는 본인 계정 ID 로 바꾼다.

---

## 0. 먼저 준비할 것

| | |
|---|---|
| 도메인 | API 는 `api.moeum.store`. prod 프로파일이 `cookie-secure: true` 라 **HTTPS 없이는 로그인이 안 된다** |
| 카카오 | REST API 키, Redirect URI(`https://api.moeum.store/auth/kakao/callback`). Client Secret 은 선택 |
| 암호화 키 | `openssl rand -base64 32` 로 생성. 로컬 개발 키를 운영에 쓰지 않는다 |

point3 의 운영 서버 IP 등록은 EIP 를 받은 **6번 이후**에 한다.

---

## 1. ECR 리포지토리

```bash
aws ecr create-repository --repository-name moeum --region ap-northeast-2 --image-scanning-configuration scanOnPush=true
```

이미지가 쌓이면 스토리지 요금이 붙는다. 최근 10개만 남긴다.

```bash
aws ecr put-lifecycle-policy --repository-name moeum --region ap-northeast-2 --lifecycle-policy-text '{"rules":[{"rulePriority":1,"description":"recent 10 only","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":10},"action":{"type":"expire"}}]}'
```

---

## 2. GitHub → AWS 인증 (OIDC)

액세스 키를 GitHub 시크릿에 넣지 않는다. GitHub 이 발급한 토큰으로 역할을 잠깐 빌린다.

계정당 한 번만 하면 되는 OIDC 공급자 등록:

```bash
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com
```

신뢰 정책을 `gh-trust.json` 으로 저장한다 (`<ACCOUNT_ID>` 교체):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
      "StringLike": {
        "token.actions.githubusercontent.com:sub": [
          "repo:keeff11@131992685/moeum@1356865473:ref:refs/heads/main",
          "repo:keeff11/moeum:ref:refs/heads/main"
        ]
      }
    }
  }]
}
```

`sub` 조건이 핵심이다. **이 저장소의 main 브랜치에서 돈 워크플로만** 이 역할을 쓸 수 있다.
포크에서 돌린 워크플로는 조건에 걸려 튕긴다.

**형식이 두 개인 이유가 있다.** GitHub 은 `sub` 클레임에 소유자·저장소의 숫자 ID 를 넣는
불변(immutable) 형식을 쓴다.

```
repo:keeff11@131992685/moeum@1356865473:ref:refs/heads/main
```

문서 대부분이 안내하는 고전 형식(`repo:owner/name:ref:...`)만 적으면
`AccessDenied: Not authorized to perform sts:AssumeRoleWithWebIdentity` 로 튕긴다.
워크플로 로그에는 원인이 안 나오고, **CloudTrail 의 `AssumeRoleWithWebIdentity` 이벤트에서
실제 `sub` 값을 봐야** 알 수 있다.

```bash
aws cloudtrail lookup-events --lookup-attributes AttributeKey=EventName,AttributeValue=AssumeRoleWithWebIdentity --max-results 3 --region ap-northeast-2
```

ID 형식이 오히려 더 안전하다. 저장소 이름을 바꾸거나 소유자를 옮겨도 ID 는 그대로라
이름을 가로채는 공격이 통하지 않는다.

```bash
aws iam create-role --role-name moeum-github-deploy --assume-role-policy-document file://gh-trust.json
```

권한 정책을 `gh-policy.json` 으로 저장한다 (`<ACCOUNT_ID>` 교체):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload", "ecr:InitiateLayerUpload",
                 "ecr:PutImage", "ecr:UploadLayerPart", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"],
      "Resource": "arn:aws:ecr:ap-northeast-2:<ACCOUNT_ID>:repository/moeum" },
    { "Effect": "Allow", "Action": "ssm:SendCommand",
      "Resource": ["arn:aws:ec2:ap-northeast-2:<ACCOUNT_ID>:instance/*",
                   "arn:aws:ssm:ap-northeast-2::document/AWS-RunShellScript"] },
    { "Effect": "Allow", "Action": ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"], "Resource": "*" }
  ]
}
```

```bash
aws iam put-role-policy --role-name moeum-github-deploy --policy-name deploy --policy-document file://gh-policy.json
```

---

## 3. EC2 인스턴스 역할

인스턴스가 스스로 할 수 있어야 하는 일: SSM 명령 받기, ECR 에서 이미지 받기, 시크릿 읽기.

`ec2-trust.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [{ "Effect": "Allow", "Principal": { "Service": "ec2.amazonaws.com" }, "Action": "sts:AssumeRole" }]
}
```

```bash
aws iam create-role --role-name moeum-ec2 --assume-role-policy-document file://ec2-trust.json
```

```bash
aws iam attach-role-policy --role-name moeum-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
```

```bash
aws iam attach-role-policy --role-name moeum-ec2 --policy-arn arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly
```

시크릿 읽기 권한을 `ec2-secrets.json` 으로 저장한다 (`<ACCOUNT_ID>` 교체):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"],
      "Resource": [
        "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/moeum/prod",
        "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/moeum/prod/*"
      ] },
    { "Effect": "Allow", "Action": "kms:Decrypt", "Resource": "*",
      "Condition": { "StringEquals": { "kms:ViaService": "ssm.ap-northeast-2.amazonaws.com" } } }
  ]
}
```

```bash
aws iam put-role-policy --role-name moeum-ec2 --policy-name read-secrets --policy-document file://ec2-secrets.json
```

**리소스가 두 줄인 이유가 있다.** `GetParametersByPath` 는 하위 파라미터가 아니라
**경로 노드 자체**(`parameter/moeum/prod`)에 대한 권한을 검사한다.
`/*` 만 적으면 `AccessDeniedException` 이 나고, 첫 배포의 시크릿 주입 단계에서 터진다.

```bash
aws iam create-instance-profile --instance-profile-name moeum-ec2
```

```bash
aws iam add-role-to-instance-profile --instance-profile-name moeum-ec2 --role-name moeum-ec2
```

---

## 4. 시크릿 등록 (Parameter Store)

값은 저장소에 절대 넣지 않는다. 배포할 때마다 인스턴스가 여기서 받아 간다.

```bash
put() { aws ssm put-parameter --region ap-northeast-2 --name "/moeum/prod/$1" --value "$2" --type SecureString --overwrite; }
```

```bash
put MYSQL_ROOT_PASSWORD "$(openssl rand -base64 24)"
put MYSQL_PASSWORD "$(openssl rand -base64 24)"
put SELLER_CRYPTO_KEY "$(openssl rand -base64 32)"
put KAKAO_CLIENT_ID "카카오-REST-API-키"
put KAKAO_REDIRECT_URI "https://api.moeum.store/auth/kakao/callback"
put ALLOWED_ORIGINS "https://www.moeum.store,https://moeum.store,https://studio.moeum.store,https://api.moeum.store"
put DOMAIN "api.moeum.store"
put LEGACY_DOMAIN "shop1.cloud"
```

`ALLOWED_ORIGINS` 에 API 자기 자신(`https://api.moeum.store`)이 들어 있는 것은 오타가 아니다.
심사 화면이 그 출처에서 돌기 때문이다 — 아래 D-052 항목을 보라. 목록의 **첫 항목이 로그인 후
기본 복귀 지점**이라 `www` 를 맨 앞에 둔다.

**이 값은 여기 적힌 것과 실제 파라미터가 어긋난 적이 있다.** `studio.moeum.store` 가 운영에는
등록돼 있는데 문서에는 빠져 있어서, 프론트 문의를 "미등록" 으로 잘못 진단할 뻔했다.
`--overwrite` 로 통째로 덮어쓰는 값이라 **고치기 전에 반드시 현재 값을 읽는다**:

```bash
aws ssm get-parameter --region ap-northeast-2 --name /moeum/prod/ALLOWED_ORIGINS --with-decryption --query Parameter.Value --output text
```

### 버킷 CORS 는 이 목록과 같이 고친다

**`ALLOWED_ORIGINS` 를 고치면 S3 버킷 CORS 도 같이 고친다.** 둘은 같은 목록인데 사는 곳이
달라 갈라진다. 이미지 업로드는 두 구간을 지나고 각 구간이 다른 목록을 본다 (D-022).

```
프론트 → API   POST /seller/sale-forms/images/upload-url   ← ALLOWED_ORIGINS 가 본다
프론트 → S3    PUT (파일)                                   ← 버킷 CORS 가 본다
```

**한쪽만 등록되면 "URL 은 받아왔는데 업로드가 안 된다" 가 된다.** 실제로 그랬다 —
`studio.moeum.store` 가 `ALLOWED_ORIGINS` 에만 있고 버킷에는 없어서 셀러 화면의 업로드가
전부 막혔다. 브라우저 콘솔에는 CORS 오류로만 보이고 상태 코드가 안 나와 서명 문제와
구분되지 않는다. 아래 OPTIONS 확인이 그 구분을 대신한다.

`put-bucket-cors` 는 **규칙을 통째로 갈아엎는다.** `--overwrite` 같은 플래그가 없어도 그렇다.
고치기 전에 현재 값을 읽어 거기에 더한다:

```bash
aws s3api get-bucket-cors --bucket moeum-images-prod --region ap-northeast-2
```

```bash
aws s3api put-bucket-cors --bucket moeum-images-prod --region ap-northeast-2 --cors-configuration '{"CORSRules":[{"AllowedOrigins":["https://www.moeum.store","https://moeum.store","https://studio.moeum.store"],"AllowedMethods":["PUT"],"AllowedHeaders":["content-type","content-length"],"MaxAgeSeconds":3000}]}'
```

`ALLOWED_ORIGINS` 와 다른 점은 `https://api.moeum.store` 가 빠지는 것 하나다 —
심사 화면은 이미지를 올리지 않는다. 읽기(GET)도 넣지 않는다. 공개 읽기라
`<img>` 로 그냥 뜨고, CORS 는 스크립트가 바이트를 읽을 때만 필요하다.

`AllowedHeaders` 가 둘뿐인 것은 서명에 들어가는 헤더가 그 둘이기 때문이다(D-022).
프론트가 PUT 에 헤더를 더 얹으면 — 공통 axios 인스턴스의 `Authorization` 인터셉터가
흔한 원인이다 — 프리플라이트가 거기서 막힌다. 그때는 헤더를 빼는 쪽이 맞다.
목록을 넓혀도 서명이 어차피 거부한다.

**확인은 브라우저 없이 한다.** 프리플라이트를 직접 쏘면 출처별로 답이 바로 나온다.

```bash
curl -s -i -X OPTIONS "https://moeum-images-prod.s3.ap-northeast-2.amazonaws.com/sale-forms/1/preflight-check.webp" -H "Origin: https://studio.moeum.store" -H "Access-Control-Request-Method: PUT" -H "Access-Control-Request-Headers: content-type"
```

`200` 과 `Access-Control-Allow-Origin` 이 나오면 통과다. 미등록 출처는 `403 AccessForbidden`
(`CORSResponse: This CORS request is not allowed`) 이고, **프론트가 보는 "CORS 오류" 의
정체가 이것이다.** 이 curl 이 200 인데도 업로드가 실패하면 CORS 가 아니라 서명 문제다 —
`contentLength` 가 실제 파일 크기와 다르거나 `Content-Type` 이 발급값과 다른 경우다.

실패한 프리플라이트는 캐시되지 않으므로 적용 즉시 반영된다. 반대로 **성공한 결과는
`MaxAgeSeconds` 만큼(50분) 캐시된다** — 규칙을 좁힐 때는 바로 반영되지 않는다.

### 운영자 명단 (D-055)

`/admin/*` 은 **앱이 직접 막는다.** 세션의 카카오 회원번호가 이 목록에 있어야 통과한다.
프록시 기본인증은 걷어냈다 — 그걸 걸면 프론트가 자기 도메인에서 이 API 를 부를 수 없다.

```bash
put ADMIN_KAKAO_IDS "123456789,987654321"
```

**회원번호를 알아내는 방법:** 운영자가 `https://www.moeum.store` 에서 카카오로 한 번
로그인한 뒤 `GET /me` 를 열면 `kakaoId` 가 보인다. 그 값을 쉼표로 이어 붙인다.

**비어 있으면 아무도 운영자가 아니다.** 파라미터가 없어도 앱은 정상 기동하고 심사 API 만
닫힌다 — 설정 하나에 서비스 전체가 멈추지 않게 기본값을 비워 뒀다. 반대로 허용 출처
목록처럼 "비어 있으면 검사를 거른다" 로 만들지는 않았다. 그랬다면 설정이 빠진 것이 곧
승인 API 전면 개방이 된다.

명단을 바꾸려면 파라미터를 고치고 재배포한다. 테이블이 아니라 환경변수인 이유와
되돌릴 조건은 D-055 에 적어 뒀다.

`SELLER_CRYPTO_KEY` 는 **한 번 정하면 못 바꾼다.** 이 키로 암호화된 사업자번호·정산계좌를
복호화할 수 없게 된다. 별도로 안전한 곳에 백업해 둔다.

### point3 키 두 개 (2026-09-09 수령)

point3 가 주는 값은 **성격이 정반대인 두 개**다. 같이 다루면 안 된다.

```bash
# 연동키 = 서버 API 토큰. Authorization: Bearer 로만 나간다.
# 히스토리에 남지 않게 셸에 직접 치지 않는다
read -rs P3_TOKEN && put POINT3_API_TOKEN "$P3_TOKEN" && unset P3_TOKEN

# client id. 결제창을 띄우려고 브라우저에 그대로 내려가는 공개값이다
aws ssm put-parameter --region ap-northeast-2 \
    --name /moeum/prod/POINT3_CLIENT_ID --value "client-..." --type String --overwrite
```

`POINT3_BASE_URL` 은 등록하지 않는다. 기본값(`https://api.point3.io`)이 운영 주소와 같다.

### 만료 홀드 point3 확인 스위치 (D-063)

기본값이 **켜짐**이라 등록하지 않아도 된다. 만료 배치가 재고를 풀기 전에 point3 에
구매자 확정을 묻는 기능이다. **이상하면 끄고 다시 배포한다** — 코드를 되돌리지 않아도
이전 동작(만료되면 묻지 않고 푼다)으로 돌아간다.

```bash
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/HOLD_EXPIRY_POINT3_CHECK --value false --type String --overwrite
```

`.env` 는 `deploy.sh` 가 만들 때만 갱신된다. 파라미터만 바꾸고 재시작하면 반영되지 않는다.

### 스마트택배 배송조회 키 (D-048)

**무료 이용권이 월 100건이다.** 그래서 키와 별개인 스위치를 뒀다 — 키를 넣어 두고도
꺼 둘 수 있다. **기본값이 꺼짐이라 키만 등록하면 아직 한 건도 안 나간다.**

```bash
read -rs ST_KEY && put SMART_TRACKER_API_KEY "$ST_KEY" && unset ST_KEY
```

켤 때만 스위치를 올린다. 시크릿이 아니라 `String` 이다.

```bash
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/SMART_TRACKER_ENABLED --value true --type String --overwrite
```

끄는 것도 같은 명령에 `false` 다. **키를 지우지 않는다** — 급할 때 번거롭고 다시 찾아야 한다.

한도가 걱정되면 꺼 둔 채로 배포해도 된다. 그때 구매자 화면은 배송조회 대신
송장번호를 보여 주고, 셀러의 송장 등록은 택배사를 직접 입력받아 그대로 동작한다.

어느 값이 어느 쪽인지 헷갈리면 **형식으로 가른다** — client id 는 `client-` + UUID 다
(`point3-api.md` 10절). 연동키가 `client-` 로 시작하면 두 값이 뒤바뀐 것이다.

**둘이 컨테이너까지 가려면 `docker-compose.prod.yml` 의 `environment` 에도 이름이 있어야 한다.**
Parameter Store 에만 넣으면 `.env` 에는 들어가지만 앱은 못 본다 — 토큰이 없던 동안에는
드러나지 않던 구멍이라 키를 받은 날 같이 고쳤다.

**`KAKAO_CLIENT_SECRET` 은 등록하지 않았다.** Parameter Store 는 빈 값을 저장할 수 없고
(최소 1자), 카카오 Client Secret 은 선택 기능이라 안 쓰는 구성이 정상이기 때문이다.
`deploy.sh` 가 파라미터가 없으면 빈 값으로 채워 주므로 앱은 정상 기동한다.

나중에 Client Secret 을 쓰기로 하면 이것만 추가하고 재배포하면 된다.

```bash
put KAKAO_CLIENT_SECRET "카카오-콘솔에서-발급한-값"
```

### 카카오 알림톡 (SOLAPI)

**`NOTIFY_PROVIDER` 를 `solapi` 로 바꾸는 순간 실제로 발송된다.** 건당 단가가 붙고,
잘못 보내면 구매자에게 간 것을 되돌릴 수 없다. 아래 다섯 개를 **다 채운 뒤에** 바꾼다.

```bash
# API Key / Secret — SOLAPI 콘솔에서 발급. Secret 은 서명에만 쓰이고 요청에 실리지 않는다
read -rs SOLAPI_KEY && put SOLAPI_API_KEY "$SOLAPI_KEY" && unset SOLAPI_KEY
read -rs SOLAPI_SECRET && put SOLAPI_API_SECRET "$SOLAPI_SECRET" && unset SOLAPI_SECRET

# 발신프로필 ID(pfId) 와 승인된 템플릿 ID. 비밀은 아니지만 같이 둔다
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/SOLAPI_PF_ID --value "KA01PF..." --type String --overwrite

# 템플릿 id — 승인된 것만 채운다. 안 채운 이벤트는 로그만 남고 넘어간다.
# 이름은 OutboxEventType 의 상수명 그대로다
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/SOLAPI_TEMPLATE_ORDER_PAID --value "KA01TP..." --type String --overwrite
# 그 외 여덟 개: SECOND_PAYMENT_DUE · SECOND_PAID · SHIPPED · PROGRESS_CHANGED ·
#               RECRUITMENT_SUCCEEDED · RECRUITMENT_FAILED · SECOND_PAYMENT_OVERDUE ·
#               REFUND_COMPLETED
# SECOND_PAYMENT_DUE 가 가장 중요하다 — 이게 안 나가면 구매자는 잔금을 낼 줄 모른다

# 발신번호. SOLAPI 에 사전 등록된 번호여야 한다 —
# 알림톡이 막힌 수신자에게 문자로 대체 발송될 때 쓰인다
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/SOLAPI_FROM --value "0212345678" --type String --overwrite

# 다 채운 뒤 마지막에 켠다
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/NOTIFY_PROVIDER --value "solapi" --type String --overwrite
```

**승인된 템플릿이 있는 이벤트만 나간다.** 승인된 넷(1차금 결제 완료 · 2차금 청구 · 발송 완료 ·
단독 판매 결제 완료)과 `pfId` 는 **`docker-compose.prod.yml` 에 기본값으로 들어 있다** (D-065) —
파라미터를 따로 넣지 않아도 된다. 파라미터가 있으면 그쪽이 이긴다.

> **⚠ 템플릿이 추가로 승인되면 id 만 넣어서는 안 나간다 (D-065).** 템플릿마다 변수 이름이
> 달라서 `AlimtalkMessageFactory` 에 그 이벤트의 변수를 채우는 코드가 있어야 한다.
> 없으면 `[알림/변수미정]` WARN 을 남기고 보내지 않는다.
아홉 개 이벤트의 자리를 `application.yml` 과 `docker-compose.prod.yml` 에 미리 다 뚫어 두었다
(D-062). 새 이벤트 타입을 만들 때만 두 파일에 이름을 추가하고, `NotifyConfigWiringTest` 가
빠진 자리를 잡는다.

> **⚠ 예전에는 이 설명대로 해도 알림톡이 나가지 않았다 (D-062).**
> `application.yml` 의 `prod` 문서에 `moeum.notify` 블록이 통째로 빠져 있어서,
> `NOTIFY_PROVIDER=solapi` 를 넣어도 읽는 자리가 없어 `moeum.notify.provider` 가
> 정의되지 않았다. 그러면 발송기를 `LoggingNotificationSender`(`matchIfMissing = true`)가
> 잡아 **예외도 경고도 없이 로그만 남는다.** 지금은 블록이 있고 테스트가 지킨다.

### 테스트 수신번호 — 구매자에게 안 가게 하고 켜는 법

수신번호 정책이 정해지기 전까지는 **모든 알림을 한 번호로 몰아** 보낼 수 있다.
이 값이 있으면 구매자에게는 한 통도 가지 않는다.

```bash
aws ssm put-parameter --region ap-northeast-2     --name /moeum/prod/SOLAPI_TEST_RECIPIENT --value "01012345678" --type String --overwrite
```

**실서비스로 넘어갈 때 반드시 지운다.** 잊으면 모든 구매자의 알림이 한 사람에게만 간다 —
잊기 쉬운 자리라 발송할 때마다 `[알림/테스트수신]` WARN 을 남긴다. 지우는 명령은 이것이다.

```bash
aws ssm delete-parameter --region ap-northeast-2 --name /moeum/prod/SOLAPI_TEST_RECIPIENT
```

**⚠ 이 값을 지우기 전에 수신번호 문제를 정리한다.** 없으면 배송지 전화번호로 보내는데,
구매자 본인 번호가 DB 에 없어서다. 선물 주문이면 결제 알림이 받는 사람에게 간다 (D-040).

---

## 5. 보안 그룹

```bash
VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text --region ap-northeast-2)
```

```bash
SG_ID=$(aws ec2 create-security-group --group-name moeum-web --description "moeum web" --vpc-id "$VPC_ID" --query GroupId --output text --region ap-northeast-2) && echo "SG_ID=$SG_ID"
```

```bash
for port in 80 443; do aws ec2 authorize-security-group-ingress --group-id "$SG_ID" --protocol tcp --port $port --cidr 0.0.0.0/0 --region ap-northeast-2; done
```

**22번은 열지 않는다.** 접속은 SSM Session Manager 로 한다. 열린 포트가 없으면 스캔 대상도 아니다.
MySQL 3306 도 열지 않는다. 컨테이너끼리는 도커 네트워크로 붙는다.

---

## 6. 인스턴스 시작 + 고정 IP

```bash
AMI=$(aws ssm get-parameters --region ap-northeast-2 --names /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query 'Parameters[0].Value' --output text) && echo "AMI=$AMI"
```

```bash
INSTANCE_ID=$(aws ec2 run-instances --region ap-northeast-2 --image-id "$AMI" --instance-type t3.small --iam-instance-profile Name=moeum-ec2 --security-group-ids "$SG_ID" --block-device-mappings '[{"DeviceName":"/dev/xvda","Ebs":{"VolumeSize":30,"VolumeType":"gp3","DeleteOnTermination":true}}]' --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=moeum-prod}]' --metadata-options 'HttpTokens=required' --query 'Instances[0].InstanceId' --output text) && echo "INSTANCE_ID=$INSTANCE_ID"
```

키페어를 안 만든다. SSH 를 안 쓰기 때문이다.

```bash
ALLOC_ID=$(aws ec2 allocate-address --domain vpc --region ap-northeast-2 --query AllocationId --output text) && aws ec2 associate-address --instance-id "$INSTANCE_ID" --allocation-id "$ALLOC_ID" --region ap-northeast-2
```

```bash
aws ec2 describe-addresses --allocation-ids "$ALLOC_ID" --region ap-northeast-2 --query 'Addresses[0].PublicIp' --output text
```

**이 IP 를 point3 에 운영 서버로 등록한다** (`docs/point3-api.md` 9절).
Fargate 대신 EC2 를 택한 이유가 여기다. 고정 IP 가 공짜로 나온다.

---

## 7. 인스턴스 초기 세팅

```bash
aws ssm start-session --target "$INSTANCE_ID" --region ap-northeast-2
```

접속되면 이 저장소의 [`deploy/bootstrap-ec2.sh`](../deploy/bootstrap-ec2.sh) 내용을 붙여넣고 `sudo bash` 로 실행한다.
도커·compose 플러그인·jq 설치, 스왑 2GB, 타임존 KST 를 세팅한다.

스왑은 선택이 아니다. 2GB 에 JVM 과 MySQL 이 같이 사는 구성이라,
없으면 트래픽 피크에 OOM Killer 가 MySQL 을 죽인다.

---

## 8. GitHub 시크릿 두 개

저장소 → Settings → Secrets and variables → Actions

| 이름 | 값 |
|---|---|
| `AWS_ROLE_ARN` | `arn:aws:iam::<ACCOUNT_ID>:role/moeum-github-deploy` |
| `EC2_INSTANCE_ID` | 6번에서 나온 `i-...` |

---

## 9. DNS (가비아)

도메인은 `shop1.cloud`, 네임서버는 가비아(`ns.gabia.co.kr`)다.
가비아 My가비아 → DNS 관리툴 → 도메인 선택 → DNS 설정 에서 A 레코드를 고친다.

| 호스트 | 타입 | 값 |
|---|---|---|
| `@` | A | 6번의 EIP |
| `www` | A | 6번의 EIP |

기존 값 `52.78.173.129` 는 예전에 쓰던 ALB 의 IP 다.
**ALB 는 IP 가 바뀌는 자원이라 A 레코드에 박으면 안 된다.** 지금 그 IP 는 남의 로드밸런서를 가리키고 있다.
EIP 는 해제하기 전까지 고정이므로 A 레코드로 박아도 된다.

MX 레코드가 없어 메일은 영향받지 않는다.

**바꾸기 전에 TTL 을 300초로 낮춰 두면** 전파를 30분씩 기다리지 않아도 된다.
반영됐는지는 이렇게 확인한다.

```bash
nslookup shop1.cloud 8.8.8.8
```

DNS 가 아직 안 붙은 상태로 배포하면 인증서 발급이 실패하고 Caddy 가 재시도를 반복한다.
**DNS 를 먼저 붙이고 배포한다.**

`www` 도 같은 EIP 를 가리켜야 한다. Caddy 가 `www.shop1.cloud` 인증서도 함께 발급하고
본 도메인으로 301 리다이렉트한다. 레코드를 지우면 인증서 발급이 실패하며 재시도 로그가 쌓인다.

---

## 10. 배포

```bash
git push origin main
```

끝이다. 이후로는 푸시할 때마다 자동으로 돈다.

---

## 운영

**로그**

```bash
aws ssm start-session --target <INSTANCE_ID> --region ap-northeast-2
```

```bash
sudo docker compose -f /opt/moeum/docker-compose.prod.yml logs -f app
```

**롤백** — 이미지 태그가 커밋 SHA 라 되돌릴 대상이 분명하다.

```bash
sudo AWS_REGION=ap-northeast-2 /opt/moeum/deploy.sh <ACCOUNT_ID>.dkr.ecr.ap-northeast-2.amazonaws.com/moeum:<이전-커밋-SHA>
```

**DB 백업** — RDS 가 아니므로 자동 백업이 없다. EBS 스냅샷을 하루 한 번 잡는다.
AWS 콘솔 → EC2 → Lifecycle Manager → 스냅샷 정책, 대상 태그 `Name=moeum-prod`,
하루 1회 · 7일 보관. 월 $1 미만이다. 이걸 안 걸면 인스턴스가 죽을 때 데이터도 같이 죽는다.

**비용** — t3.small $19 + EBS 30GB $2.5 + EIP $3.6 + ECR·스냅샷 $1 = **월 $26 안팎**.

---

## 알아둘 것

**배포 중 수 초간 끊긴다.** 인스턴스가 한 대라 무중단이 안 된다.
`server.shutdown: graceful` 이 켜져 있어 처리 중이던 결제 요청은 마무리되고 종료된다.

**Flyway 는 앱 기동 시 자동 실행된다.** 마이그레이션이 실패하면 앱이 안 뜨고,
헬스체크가 통과하지 않아 배포 잡이 실패한다. **이전 컨테이너는 남아 있지 않다** —
`docker compose up` 이 먼저 교체하므로 그동안 API 가 멈춘다. 되돌리려면 위의 롤백을 돌린다.

**⚠ 운영 DB 콘솔을 열어 둔 채 배포하지 않는다 (2026-09-17 장애).**
`docker exec ... mysql` 로 조회만 하고 나가지 않은 세션이 트랜잭션을 연 채 남아 있으면,
그 세션이 건드린 테이블의 `ALTER TABLE` 이 메타데이터 잠금을 **끝없이 기다린다.**
V18 이 `buyer` 에서 이렇게 10분 멈췄고 그동안 API 가 내려가 있었다. 로그는
`Migrating schema ... to version` 에서 조용히 멈추고 에러가 없다. 확인과 해제:

```sql
SELECT id, user, host, command, time, state FROM information_schema.processlist ORDER BY time DESC;
SELECT trx_mysql_thread_id, trx_started FROM information_schema.innodb_trx;  -- 오래된 것이 범인
KILL <thread id>;
```

잠금이 풀리면 멈춰 있던 앱이 그대로 이어서 뜬다 — 재배포는 필요 없다.
이때 GitHub Actions 잡은 실패로 남는다. `aws ssm wait` 가 100초만 기다리고 끝나기 때문이라,
**잡이 실패해도 인스턴스에서는 배포가 계속 돌고 있을 수 있다** — `get-command-invocation` 으로 본다.

**시크릿을 바꿀 때는** Parameter Store 값만 고치고 재배포하면 된다.
`deploy.sh` 가 매번 `.env` 를 새로 만든다.

---

## 고아 이미지 청소 켜기 (D-041)

배치는 코드에 들어가 있지만 **기본값이 dry-run 이라 아무것도 지우지 않는다.**
아래 순서를 지킨다. 되돌릴 수 없는 작업이라 순서가 곧 안전장치다.

**1. 버킷 versioning 을 켠다.** 잘못 지웠을 때 되돌릴 수 있는 유일한 수단이다.

```bash
aws s3api put-bucket-versioning --bucket moeum-images-prod --versioning-configuration Status=Enabled
```

이전 버전이 무한정 쌓이지 않게 30일 만료를 건다. **수명주기 규칙은 여기에 쓴다** —
고아 판정 자체에는 쓸 수 없다(D-041).

```bash
aws s3api put-bucket-lifecycle-configuration --bucket moeum-images-prod --lifecycle-configuration '{"Rules":[{"ID":"expire-noncurrent","Status":"Enabled","Filter":{"Prefix":""},"NoncurrentVersionExpiration":{"NoncurrentDays":30}}]}'
```

**2. EC2 역할에 목록·삭제 권한을 준다.** 지금 앱 권한은 `PutObject` 뿐이다.
`ec2-image-sweep.json` 으로 저장한다.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::moeum-images-prod",
      "Condition": { "StringLike": { "s3:prefix": "sale-forms/*" } } },
    { "Effect": "Allow", "Action": "s3:DeleteObject",
      "Resource": "arn:aws:s3:::moeum-images-prod/sale-forms/*" }
  ]
}
```

```bash
aws iam put-role-policy --role-name moeum-ec2 --policy-name image-sweep --policy-document file://ec2-image-sweep.json
```

`sale-forms/*` 로 좁힌 이유는 배치가 그 접두사만 훑기 때문이다.
버킷에 다른 용도의 경로가 생겨도 삭제 권한이 닿지 않는다.

**3. dry-run 상태로 1~2주 로그를 본다.** 매일 04:00 에 한 줄씩 남는다.

```bash
sudo docker logs moeum 2>&1 | grep "고아 이미지\|참조 이미지 키"
```

보는 것은 둘이다.

- `참조 이미지 키: sale_form_image=N건` — **N 이 0 이면 조회가 깨진 것이다.**
  참조처별 건수가 갑자기 0 이 되는 것이 이 배치에서 가장 위험한 고장이고,
  건수가 적으면 비율 안전장치에도 안 걸린다
- `고아 이미지 청소 (dry-run, 지우지 않음): ... 키=[...]` — 이 목록에 **살아 있어야 할
  이미지가 섞여 있으면 참조처를 빠뜨린 것이다.** 섞여 있으면 4번으로 넘어가지 않는다

2번을 건너뛰면 `ListBucket` 이 403 이라 배치가 아무 일도 못 한다.
그 상태에서도 로그는 조용하니 **"고아 없음" 만 보고 정상이라고 판단하지 않는다.**

**4. dry-run 을 끈다.** Parameter Store 에 값을 넣고 재배포한다.

```bash
aws ssm put-parameter --name /moeum/prod/ORPHAN_SWEEP_DRY_RUN --value false --type String --overwrite
```

되돌릴 때는 값을 `true` 로 바꾸고 재배포한다. 크론 자체를 끄려면
`ORPHAN_SWEEP_CRON` 을 `-` 로 둔다.
