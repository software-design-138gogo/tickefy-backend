# syntax=docker/dockerfile:1.7

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

COPY pom.xml pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring

COPY --from=build /app/target/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
EXPOSE 8080

USER spring
ENTRYPOINT ["java", "-jar", "app.jar"]
