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
mv .env.new .env

# ── 2. ECR 로그인 ──────────────────────────────────────────────────────
REGISTRY="${IMAGE%%/*}"
aws ecr get-login-password --region "$REGION" \
	| docker login --username AWS --password-stdin "$REGISTRY"

# ── 3. 교체 ────────────────────────────────────────────────────────────
docker compose -f docker-compose.prod.yml pull
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
