# syntax=docker/dockerfile:1

# ── 빌드 ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

# 의존성 선언만 먼저 복사한다. src 만 바뀐 커밋에서는 이 레이어가 캐시에서 재사용된다.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle gradle.properties ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 CI 가 이미 돌렸다. 이미지 빌드에서 Testcontainers 를 또 띄우지 않는다.
RUN ./gradlew --no-daemon bootJar -x test

# jar 를 레이어로 분해한다. 의존성이 그대로면 배포 시 application 레이어만 전송된다.
RUN java -Djarmode=layertools -jar build/libs/*.jar extract --destination /layers

# ── 실행 ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl 은 헬스체크용, tzdata 는 KST 판단용이다.
# EOB 차단 시간대(23:30~00:30 KST)가 시각에 걸려 있어 타임존을 흘려보내면 안 된다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 moeum

ENV TZ=Asia/Seoul
# t3.small(2GB)에 MySQL 과 같이 산다. 컨테이너가 메모리를 다 물면 OOM Killer 가 DB 를 친다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8"
ENV SPRING_PROFILES_ACTIVE=prod

WORKDIR /app
COPY --from=build --chown=moeum:moeum /layers/dependencies/ ./
COPY --from=build --chown=moeum:moeum /layers/spring-boot-loader/ ./
COPY --from=build --chown=moeum:moeum /layers/snapshot-dependencies/ ./
COPY --from=build --chown=moeum:moeum /layers/application/ ./

USER moeum
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
