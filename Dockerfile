# ── 1단계: 빌드 ──────────────────────────────────────────────
# 의존성 정의 파일을 소스보다 먼저 COPY해서, 소스만 바뀐 빌드에서는
# 의존성 다운로드 레이어가 캐시로 재사용되게 한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
# 의존성만 미리 받아 캐시 레이어 생성 (실패해도 다음 단계에서 받으므로 무시)
RUN sh gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src/ src/
# 테스트는 CI의 test job에서 이미 돌았으므로 이미지 빌드에서는 건너뛴다
RUN sh gradlew --no-daemon bootJar -x test

# layered jar 추출: 의존성/로더/스냅샷/애플리케이션 코드를 별도 레이어로 분리
# → 코드만 바뀌면 마지막 얇은 레이어만 다시 push/pull 된다
RUN java -Djarmode=layertools -jar build/libs/*.jar extract --destination extracted

# ── 2단계: 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# root로 앱을 돌리지 않는다 — 컨테이너 탈출 시 피해 최소화
RUN useradd --system --no-create-home spring
USER spring

# 변경 빈도가 낮은 레이어부터 COPY (캐시 적중률 순서)
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8080
# 힙 크기 등 JVM 옵션은 compose의 JAVA_TOOL_OPTIONS 환경변수로 주입
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
