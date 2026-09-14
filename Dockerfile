# syntax=docker/dockerfile:1.7

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
    cp "$SERVICE"/target/*.jar /tmp/application.jar

FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache curl && \
    addgroup -S -g 10001 application && \
    adduser -S -D -H -u 10001 -G application application

WORKDIR /application

COPY --from=build --chown=application:application /tmp/application.jar application.jar

USER application

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=25.0"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/application/application.jar"]
