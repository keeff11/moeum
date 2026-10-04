#!/usr/bin/env bash
# EC2 안에서 도는 배포 스크립트. GitHub Actions 가 SSM 으로 호출한다.
# 사용법: deploy.sh <ECR 이미지 URI>
set -euo pipefail

IMAGE="${1:?사용법: deploy.sh <ECR 이미지 URI>}"
REGION="${AWS_REGION:-ap-northeast-2}"
APP_DIR=/opt/moeum
SSM_PATH=/moeum/prod

cd "$APP_DIR"

# ── 1. 시크릿 ──────────────────────────────────────────────────────────
# 값은 저장소에 없다. Parameter Store 에만 있고 배포할 때마다 여기서 받아 온다.
# 시크릿을 바꾸려면 파라미터만 고치고 재배포하면 된다. 이미지는 손대지 않는다.
umask 077
aws ssm get-parameters-by-path \
	--path "$SSM_PATH" --with-decryption --recursive \
	--region "$REGION" \
	--query 'Parameters[].{name:Name,value:Value}' --output json \
	| jq -r '.[] | "\(.name | split("/") | last)=\(.value | @sh)"' > .env.new

echo "APP_IMAGE='${IMAGE}'" >> .env.new

# Parameter Store 는 빈 값을 저장할 수 없다(최소 1자). 그런데 prod 프로파일의
# ${KAKAO_CLIENT_SECRET} 에는 기본값이 없어서, 파라미터가 없으면 앱이 기동조차 못 한다.
# Client Secret 을 안 쓰는 구성에서도 뜨도록 빈 값을 채워 준다.
# KakaoOAuthClient 는 값이 비어 있으면 토큰 요청에 secret 을 붙이지 않는다.
grep -q '^KAKAO_CLIENT_SECRET=' .env.new || echo "KAKAO_CLIENT_SECRET=''" >> .env.new

mv .env.new .env

# ── 2. ECR 로그인 ──────────────────────────────────────────────────────
REGISTRY="${IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" \
	| docker login --username AWS --password-stdin "$REGISTRY"

# ── 3. 교체 ────────────────────────────────────────────────────────────
docker compose -f docker-compose.prod.yml pull

# 교체 직전에 오래 열린 트랜잭션이 있는지 본다 (2026-09-17 장애).
# 있으면 새 앱의 Flyway ALTER TABLE 이 메타데이터 잠금을 끝없이 기다리는데,
# 이전 컨테이너는 이미 내려가 있어 그동안 API 가 멈춘다. 교체 전에 멈추면 이전 앱이 계속 서비스한다.
# lock_wait_timeout 으로 끊지 않는다 — MySQL 은 DDL 이 롤백되지 않아 Flyway 가 실패 기록을 남기고,
# 잠금이 풀려도 repair 전까지 앱이 재시작마다 validate 에서 떨어진다.
TRX_AGE_LIMIT=30
if [ "$(docker inspect -f '{{.State.Running}}' moeum-mysql 2>/dev/null || echo false)" = "true" ]; then
	set +e
	long_trx="$(docker exec -i moeum-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B' <<SQL
SELECT t.trx_mysql_thread_id, TIMESTAMPDIFF(SECOND, t.trx_started, NOW()), p.user, p.host, p.command,
       LEFT(COALESCE(p.info, ''), 80)
FROM information_schema.innodb_trx t
LEFT JOIN information_schema.processlist p ON p.id = t.trx_mysql_thread_id
WHERE t.trx_started < NOW() - INTERVAL ${TRX_AGE_LIMIT} SECOND
ORDER BY t.trx_started;
SQL
)"
	trx_rc=$?
	set -e
	if [ $trx_rc -ne 0 ]; then
		echo "경고: 열린 트랜잭션 검사에 실패했다. 검사 없이 계속한다" >&2
	elif [ -n "$long_trx" ]; then
		{
			echo "배포 중단: ${TRX_AGE_LIMIT}초 넘게 열린 트랜잭션이 있다. 이전 앱은 그대로 돌고 있다."
			echo "thread_id  초  user  host  command  query"
			echo "$long_trx"
			echo "콘솔 세션이면 COMMIT/ROLLBACK 하고 나가거나 KILL <thread_id> 한 뒤 다시 배포한다 (docs/deployment.md)."
		} >&2
		exit 1
	fi
fi

docker compose -f docker-compose.prod.yml up -d --remove-orphans

# ── 4. 확인 ────────────────────────────────────────────────────────────
# 컨테이너가 떴다고 배포 성공이 아니다. Flyway 가 끝나고 readiness 가 떠야 성공이다.
echo "기동 대기..."
for _ in $(seq 1 60); do
	status="$(docker inspect -f '{{.State.Health.Status}}' moeum-app 2>/dev/null || echo starting)"
	if [ "$status" = "healthy" ]; then
		echo "배포 성공: $IMAGE"
		docker image prune -af --filter "until=72h" > /dev/null || true
		exit 0
	fi
	if [ "$status" = "unhealthy" ]; then
		break
	fi
	sleep 5
done

echo "배포 실패: 앱이 healthy 로 올라오지 않았다" >&2
docker compose -f docker-compose.prod.yml logs --tail 100 app >&2
exit 1
