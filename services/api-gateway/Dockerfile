# syntax=docker/dockerfile:1.7

# ============================================================
# Stage 1: Build executable Spring Boot JAR
# ============================================================
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

# Copy build descriptors first to maximize dependency cache reuse.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
  mvn -B -ntp -DskipTests dependency:go-offline

COPY src/ src/

# Tests are run before image build in CI/local workflow.
RUN --mount=type=cache,target=/root/.m2 \
  mvn -B -ntp clean package -DskipTests


# ============================================================
# Stage 2: Extract Spring Boot layers
# ============================================================
FROM eclipse-temurin:25-jre AS extract

WORKDIR /workspace

COPY --from=build \
  /workspace/target/api-gateway-*.jar \
  application.jar

RUN java \
  -Djarmode=tools \
  -jar application.jar \
  extract \
  --layers \
  --destination extracted


# ============================================================
# Stage 3: Minimal runtime image
# ============================================================
FROM eclipse-temurin:25-jre AS runtime

WORKDIR /application

ENV SERVER_PORT=8080 \
  SPRING_PROFILES_ACTIVE=docker \
  HOME=/tmp \
  JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp"

# Copy stable dependencies before frequently changing application code.
COPY --from=extract --chown=10001:10001 \
  /workspace/extracted/dependencies/ \
  ./

COPY --from=extract --chown=10001:10001 \
  /workspace/extracted/spring-boot-loader/ \
  ./

COPY --from=extract --chown=10001:10001 \
  /workspace/extracted/snapshot-dependencies/ \
  ./

COPY --from=extract --chown=10001:10001 \
  /workspace/extracted/application/ \
  ./

COPY --chmod=0555 --chown=10001:10001 \
  docker/healthcheck.sh \
  /application/healthcheck.sh

# Run application as a non-root numeric user.
USER 10001:10001

EXPOSE 8080

HEALTHCHECK \
  --interval=10s \
  --timeout=3s \
  --start-period=30s \
  --retries=5 \
  CMD ["/application/healthcheck.sh"]

STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "application.jar"]