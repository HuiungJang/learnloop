FROM node:22-alpine

WORKDIR /opt/learnloop-runner

ENV npm_config_audit=false \
    npm_config_fund=false \
    npm_config_update_notifier=false

RUN npm init -y >/dev/null \
    && npm install --omit=dev --ignore-scripts typescript@5.9.3 @types/node@22.19.19

COPY run-tests.mjs /opt/learnloop-runner/run-tests.mjs

WORKDIR /workspace

CMD ["node", "/opt/learnloop-runner/run-tests.mjs"]
