FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY backend/build.gradle.kts ./backend/build.gradle.kts
COPY backend/src ./backend/src

RUN chmod +x ./gradlew && ./gradlew :backend:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl docker-cli && addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=build /workspace/backend/build/libs/*.jar /app/app.jar

USER app

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD curl -fsS http://127.0.0.1:8080/api/health >/dev/null || exit 1

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
