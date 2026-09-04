#!/usr/bin/env bash
# EC2 최초 1회 세팅. 인스턴스 시작 후 SSM Session Manager 로 접속해 sudo 로 실행한다.
# Amazon Linux 2023 기준 (SSM 에이전트와 aws-cli v2 가 이미 들어 있다).
set -euo pipefail

COMPOSE_VERSION=v2.29.7

echo "== 패키지 =="
dnf update -y
dnf install -y docker jq

echo "== 도커 =="
systemctl enable --now docker

echo "== compose 플러그인 =="
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL \
	"https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
	-o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

echo "== 스왑 2GB =="
# 2GB 인스턴스에 JVM 과 MySQL 이 같이 산다. 스왑이 없으면 순간 피크에
# OOM Killer 가 MySQL 을 죽인다. 상시로 쓰라는 게 아니라 완충재다.
if [ ! -f /swapfile ]; then
	dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
	chmod 600 /swapfile
	mkswap /swapfile > /dev/null
	swapon /swapfile
	echo '/swapfile none swap sw 0 0' >> /etc/fstab
	sysctl -w vm.swappiness=10
	echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf
fi

echo "== 타임존 =="
timedatectl set-timezone Asia/Seoul

echo "== 배포 디렉터리 =="
mkdir -p /opt/moeum
chmod 700 /opt/moeum

echo
echo "완료. 다음은 GitHub Actions 가 채운다:"
echo "  /opt/moeum/docker-compose.prod.yml"
echo "  /opt/moeum/Caddyfile"
echo "  /opt/moeum/deploy.sh"
echo "  /opt/moeum/.env  (Parameter Store 에서 생성)"
