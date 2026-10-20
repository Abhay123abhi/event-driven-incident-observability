# syntax=docker/dockerfile:1.7

# Shared multi-module build: BuildKit reuses this one Maven package step for
# every service in docker-compose.yml instead of recompiling five times.
FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache unzip
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' ./mvnw && \
    sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# Each service runs its own executable Spring Boot JAR. Using java -jar avoids
# a missing JarLauncher class when extracting Spring Boot layers incorrectly.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 application && \
    adduser -S -D -H -u 10001 -G application application
WORKDIR /application

ARG SERVICE
COPY --from=build --chown=application:application \
    /workspace/${SERVICE}/target/${SERVICE}-1.0.0-SNAPSHOT.jar /application/app.jar

USER application
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/application/app.jar"]
