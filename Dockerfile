# syntax=docker/dockerfile:1.7

# Build dependencies stay out of the runtime image.
FROM eclipse-temurin:25-jdk-alpine AS build
RUN apk add --no-cache unzip
WORKDIR /workspace

COPY . .

ARG SERVICE

RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' ./mvnw && \
    test -n "$SERVICE" && \
    sh ./mvnw --batch-mode --no-transfer-progress \
      -pl "$SERVICE" -am -DskipTests package && \
    cp "$SERVICE"/target/*.jar /tmp/application.jar && \
    java -Djarmode=tools -jar /tmp/application.jar extract --layers --destination /tmp/layers

# The Alpine JRE and shared Boot dependency layers keep application images compact.
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 application && \
    adduser -S -D -H -u 10001 -G application application
WORKDIR /application

COPY --from=build --chown=application:application /tmp/layers/dependencies/ ./
COPY --from=build --chown=application:application /tmp/layers/spring-boot-loader/ ./
COPY --from=build --chown=application:application /tmp/layers/snapshot-dependencies/ ./
COPY --from=build --chown=application:application /tmp/layers/application/ ./

USER application
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=10 -XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
