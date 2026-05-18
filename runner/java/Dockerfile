FROM eclipse-temurin:21-jdk-alpine

ARG JUNIT_PLATFORM_VERSION=1.10.2

WORKDIR /opt/learnloop-runner

RUN apk add --no-cache --virtual .fetch-deps curl \
    && curl -fsSL \
      "https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/${JUNIT_PLATFORM_VERSION}/junit-platform-console-standalone-${JUNIT_PLATFORM_VERSION}.jar" \
      -o /opt/learnloop-runner/junit-platform-console-standalone.jar \
    && apk del .fetch-deps

COPY run-tests.sh /opt/learnloop-runner/run-tests.sh

RUN chmod 0555 /opt/learnloop-runner/run-tests.sh

WORKDIR /workspace

CMD ["/opt/learnloop-runner/run-tests.sh"]
