FROM eclipse-temurin:21-jdk-alpine

ARG JUNIT_PLATFORM_VERSION=1.10.2
ARG KOTLIN_VERSION=2.2.21

WORKDIR /opt/learnloop-runner

RUN apk add --no-cache bash \
    && apk add --no-cache --virtual .fetch-deps curl unzip \
    && curl -fsSL \
      "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
      -o /tmp/kotlin-compiler.zip \
    && unzip -q /tmp/kotlin-compiler.zip -d /opt \
    && mv /opt/kotlinc /opt/kotlin \
    && curl -fsSL \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_PLATFORM_VERSION}/junit-platform-console-standalone-${JUNIT_PLATFORM_VERSION}.jar" \
      -o /opt/learnloop-runner/junit-platform-console-standalone.jar \
    && rm -f /tmp/kotlin-compiler.zip \
    && apk del .fetch-deps

ENV KOTLIN_HOME=/opt/kotlin \
    PATH="/opt/kotlin/bin:${PATH}"

COPY run-tests.sh /opt/learnloop-runner/run-tests.sh

RUN chmod 0555 /opt/learnloop-runner/run-tests.sh

WORKDIR /workspace

CMD ["/opt/learnloop-runner/run-tests.sh"]
