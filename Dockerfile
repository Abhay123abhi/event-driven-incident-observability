# syntax=docker/dockerfile:1.7

# Build dependencies stay out of the runtime image.
FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache unzip
WORKDIR /workspace

COPY . .

# Build all five reactor modules once. BuildKit can reuse this identical stage
# across the Compose services instead of compiling the reactor five times.
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' ./mvnw && \
    sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package

# Only this lightweight extraction stage varies with the service build argument.
FROM build AS extract
ARG SERVICE
RUN test -n "$SERVICE" && \
    test -f "$SERVICE/target/$SERVICE-1.0.0-SNAPSHOT.jar" && \
    java -Djarmode=tools -jar "$SERVICE/target/$SERVICE-1.0.0-SNAPSHOT.jar" \
      extract --layers --destination /tmp/layers

# The Alpine JRE and shared Boot dependency layers keep application images compact.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 application && \
    adduser -S -D -H -u 10001 -G application application
WORKDIR /application

COPY --from=extract --chown=application:application /tmp/layers/dependencies/ ./
COPY --from=extract --chown=application:application /tmp/layers/spring-boot-loader/ ./
COPY --from=extract --chown=application:application /tmp/layers/snapshot-dependencies/ ./
COPY --from=extract --chown=application:application /tmp/layers/application/ ./

USER application
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
