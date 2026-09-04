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
| 도메인 | `shop1.cloud` (가비아). prod 프로파일이 `cookie-secure: true` 라 **HTTPS 없이는 로그인이 안 된다** |
| 카카오 | REST API 키, Redirect URI(`https://shop1.cloud/auth/kakao/callback`). Client Secret 은 선택 |
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
      "StringLike": { "token.actions.githubusercontent.com:sub": "repo:keeff11/moeum:ref:refs/heads/main" }
    }
  }]
}
```

`sub` 조건이 핵심이다. **이 저장소의 main 브랜치에서 돈 워크플로만** 이 역할을 쓸 수 있다.
포크에서 돌린 워크플로는 조건에 걸려 튕긴다.

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
      "Resource": "arn:aws:ssm:ap-northeast-2:<ACCOUNT_ID>:parameter/moeum/prod/*" },
    { "Effect": "Allow", "Action": "kms:Decrypt", "Resource": "*",
      "Condition": { "StringEquals": { "kms:ViaService": "ssm.ap-northeast-2.amazonaws.com" } } }
  ]
}
```

```bash
aws iam put-role-policy --role-name moeum-ec2 --policy-name read-secrets --policy-document file://ec2-secrets.json
```

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
put KAKAO_REDIRECT_URI "https://shop1.cloud/auth/kakao/callback"
put ALLOWED_ORIGINS "https://shop1.cloud"
put DOMAIN "shop1.cloud"
```

`SELLER_CRYPTO_KEY` 는 **한 번 정하면 못 바꾼다.** 이 키로 암호화된 사업자번호·정산계좌를
복호화할 수 없게 된다. 별도로 안전한 곳에 백업해 둔다.

**`KAKAO_CLIENT_SECRET` 은 등록하지 않았다.** Parameter Store 는 빈 값을 저장할 수 없고
(최소 1자), 카카오 Client Secret 은 선택 기능이라 안 쓰는 구성이 정상이기 때문이다.
`deploy.sh` 가 파라미터가 없으면 빈 값으로 채워 주므로 앱은 정상 기동한다.

나중에 Client Secret 을 쓰기로 하면 이것만 추가하고 재배포하면 된다.

```bash
put KAKAO_CLIENT_SECRET "카카오-콘솔에서-발급한-값"
```

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
헬스체크가 통과하지 않아 배포 잡이 실패한다. 이전 컨테이너는 계속 돌고 있다.

**시크릿을 바꿀 때는** Parameter Store 값만 고치고 재배포하면 된다.
`deploy.sh` 가 매번 `.env` 를 새로 만든다.
