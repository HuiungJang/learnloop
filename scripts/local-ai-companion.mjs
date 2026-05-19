import { spawn } from "node:child_process";
import http from "node:http";

const host = "127.0.0.1";
const port = Number(process.env.LEARNLOOP_LOCAL_AI_PORT || 4317);
const allowedOrigins = new Set([
  "http://localhost:8080",
  "http://127.0.0.1:8080",
  "http://localhost:5173",
  "http://127.0.0.1:5173"
]);
const providers = {
  codex: {
    label: "Codex CLI OAuth",
    command: commandFromEnv("LEARNLOOP_CODEX_LOGIN_COMMAND", ["codex", "login"]),
    statusCommand: commandFromEnv("LEARNLOOP_CODEX_STATUS_COMMAND", ["codex", "login", "status"]),
    connectedPattern: /logged in/i
  },
  gemini: {
    label: "Google OAuth",
    command: commandFromEnv("LEARNLOOP_GEMINI_LOGIN_COMMAND", ["gcloud", "auth", "application-default", "login"])
  }
};
const providerState = new Map();

const server = http.createServer(async (req, res) => {
  setCorsHeaders(req, res);
  if (req.method === "OPTIONS") {
    res.writeHead(204);
    res.end();
    return;
  }

  const url = new URL(req.url ?? "/", `http://${host}:${port}`);
  try {
    if (req.method === "GET" && url.pathname === "/health") {
      sendJson(res, 200, { status: "ok" });
      return;
    }

    if (req.method === "GET" && url.pathname === "/oauth/status") {
      const provider = readProvider(url.searchParams.get("provider"));
      sendJson(res, 200, stateFor(provider));
      return;
    }

    if (req.method === "POST" && url.pathname === "/oauth/start") {
      assertAllowedOrigin(req);
      const body = await readJson(req);
      const provider = readProvider(body.provider);
      await startOAuth(provider);
      sendJson(res, 202, stateFor(provider));
      return;
    }

    sendJson(res, 404, { status: "failed", message: "Not found" });
  } catch (error) {
    sendJson(res, error.status || 500, {
      status: "failed",
      message: error.status ? error.message : "Local OAuth companion failed"
    });
  }
});

server.listen(port, host, () => {
  console.log(`LearnLoop local AI companion listening on http://${host}:${port}`);
});

function commandFromEnv(name, fallback) {
  const configured = process.env[name]?.trim();
  return configured ? configured.split(/\s+/) : fallback;
}

function setCorsHeaders(req, res) {
  const origin = req.headers.origin;
  if (origin && allowedOrigins.has(origin)) {
    res.setHeader("access-control-allow-origin", origin);
    res.setHeader("vary", "origin");
  }
  res.setHeader("access-control-allow-methods", "GET,POST,OPTIONS");
  res.setHeader("access-control-allow-headers", "content-type");
  res.setHeader("access-control-allow-private-network", "true");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
}

function assertAllowedOrigin(req) {
  const origin = req.headers.origin;
  if (origin && !allowedOrigins.has(origin)) {
    throw httpError(403, "Origin is not allowed");
  }
}

function sendJson(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  res.end(`${JSON.stringify(payload)}\n`);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 10_000) throw httpError(413, "Request body too large");
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw httpError(400, "Invalid JSON body");
  }
}

function readProvider(value) {
  if (value !== "codex" && value !== "gemini") {
    throw httpError(400, "Unsupported OAuth provider");
  }
  return value;
}

async function startOAuth(provider) {
  const current = providerState.get(provider);
  if (current?.child && current.child.exitCode === null) return;

  const config = providers[provider];
  const existingConnection = await readExistingConnection(provider, config);
  if (existingConnection !== null) {
    providerState.set(provider, existingConnection);
    return;
  }

  const [command, ...args] = config.command;
  const child = spawn(command, args, {
    env: process.env,
    stdio: ["ignore", "pipe", "pipe"]
  });
  const nextState = {
    status: "running",
    provider,
    credentialLabel: config.label,
    message: `Complete ${config.label} in the opened prompt.`,
    output: "",
    child
  };
  providerState.set(provider, nextState);

  child.stdout?.on("data", (chunk) => appendOutput(nextState, chunk));
  child.stderr?.on("data", (chunk) => appendOutput(nextState, chunk));
  child.on("error", (error) => {
    nextState.status = error.code === "ENOENT" ? "missing" : "failed";
    nextState.message =
      error.code === "ENOENT"
        ? `${command} is not installed or not available in PATH.`
        : error.message;
  });
  child.on("close", (code) => {
    if (nextState.status === "missing") return;
    if (code === 0) {
      nextState.status = "connected";
      nextState.message = `${config.label} connected.`;
    } else {
      nextState.status = "failed";
      nextState.message = trimmedMessage(nextState.output) || `${config.label} exited with code ${code}.`;
    }
  });
}

async function readExistingConnection(provider, config) {
  if (!config.statusCommand) return null;

  const [command, ...args] = config.statusCommand;
  const result = await runCommand(command, args, 5000);
  if (result.errorCode === "ENOENT") {
    return {
      status: "missing",
      provider,
      credentialLabel: config.label,
      message: `${command} is not installed or not available in PATH.`
    };
  }
  if (result.timedOut || result.code !== 0 || !config.connectedPattern.test(result.output)) {
    return null;
  }

  return {
    status: "connected",
    provider,
    credentialLabel: config.label,
    message: `${config.label} connected.`
  };
}

function runCommand(command, args, timeoutMs) {
  return new Promise((resolve) => {
    let output = "";
    let settled = false;
    const child = spawn(command, args, {
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"]
    });
    const timer = setTimeout(() => {
      child.kill();
      finish({ code: null, errorCode: null, timedOut: true, output });
    }, timeoutMs);

    function finish(result) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    }

    child.stdout?.on("data", (chunk) => {
      output = `${output}${chunk.toString("utf8")}`.slice(-4000);
    });
    child.stderr?.on("data", (chunk) => {
      output = `${output}${chunk.toString("utf8")}`.slice(-4000);
    });
    child.on("error", (error) => {
      finish({ code: null, errorCode: error.code, timedOut: false, output: output || error.message });
    });
    child.on("close", (code) => {
      finish({ code, errorCode: null, timedOut: false, output });
    });
  });
}

function stateFor(provider) {
  const current = providerState.get(provider);
  if (!current) {
    return {
      status: "idle",
      provider,
      credentialLabel: providers[provider].label,
      message: ""
    };
  }
  return {
    status: current.status,
    provider,
    credentialLabel: current.credentialLabel,
    message: current.message
  };
}

function appendOutput(state, chunk) {
  state.output = `${state.output}${chunk.toString("utf8")}`.slice(-4000);
}

function trimmedMessage(value) {
  return String(value).trim().split("\n").slice(-4).join(" ").slice(0, 500);
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}
