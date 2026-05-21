FROM rust:1.95-slim-bookworm

WORKDIR /opt/learnloop-runner

COPY run-tests.sh /opt/learnloop-runner/run-tests.sh
RUN chmod 0555 /opt/learnloop-runner/run-tests.sh

WORKDIR /workspace

ENTRYPOINT ["/opt/learnloop-runner/run-tests.sh"]
