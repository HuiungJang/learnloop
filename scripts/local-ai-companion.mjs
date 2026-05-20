import { spawn } from "node:child_process";
import { randomBytes, timingSafeEqual } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { chmod, lstat, mkdir, open, readFile, stat, writeFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { BeforeSnapshotCache } from "./local-ai-before-snapshot-cache.mjs";
import { readCodexAppPresence } from "./local-ai-codex-app-adapter.mjs";
import { GitReconciliationCache, reconcileGitRepository } from "./local-ai-git-reconcile.mjs";
import { readHostProcessSnapshot } from "./local-ai-process-snapshot.mjs";
import { LocalAiSessionUploadQueue } from "./local-ai-session-upload-queue.mjs";
import { LocalAiWatcherRegistry } from "./local-ai-watcher-registry.mjs";

const DEFAULT_CONFIG_DIR = path.join(os.homedir(), ".learnloop");
const DEFAULT_TOKEN_FILE_NAME = "local-api-token";
const TOKEN_DIRECTORY_MARKER = ".learnloop-local-api-token-dir";
const host = readLoopbackHost();
const port = Number(process.env.LEARNLOOP_LOCAL_AI_PORT || 4317);
const baseUrl = localBaseUrl(host, port);
const allowedOrigins = new Set([...readDefaultAllowedOrigins(), ...readAdditionalAllowedOrigins()]);
const tokenFileOverride = process.env.LEARNLOOP_LOCAL_AI_TOKEN_FILE?.trim();
const configDirOverride = process.env.LEARNLOOP_LOCAL_AI_CONFIG_DIR?.trim();
const configDir = resolveLocalApiConfigDir(configDirOverride);
const tokenFile = resolveLocalApiTokenFile(tokenFileOverride, configDir);
const localApiToken = await ensureLocalApiToken(tokenFile, { requireManagedDirectory: Boolean(tokenFileOverride) });
const oauthStartTokens = new Map();
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
const shimEvents = [];
const maxShimEvents = 100;
const consentActions = [];
const rateLimitBuckets = new Map();
const gitReconciliationCache = new GitReconciliationCache();
const beforeSnapshotCache = new BeforeSnapshotCache();
const sessionUploadQueue = await LocalAiSessionUploadQueue.open({
  storeFile: path.join(configDir, "local-ai-session-upload-queue.json")
});
const watcherRegistry =
  new LocalAiWatcherRegistry({
    debounceMs: readNumericEnv("LEARNLOOP_LOCAL_AI_WATCH_DEBOUNCE_MS"),
    maxPendingChanges: readNumericEnv("LEARNLOOP_LOCAL_AI_WATCH_MAX_PENDING_CHANGES"),
    maxConcurrentReconciliations: readNumericEnv("LEARNLOOP_LOCAL_AI_RECONCILE_CONCURRENCY"),
    initialReconciliation: true,
    reconcileRepository: (input) =>
      reconcileGitRepository(input, {
        cache: gitReconciliationCache,
        beforeSnapshotCache
      })
  });

const server = http.createServer(async (req, res) => {
  try {
    assertAllowedHost(req);
    assertAllowedOrigin(req);
    assertLoopbackRemoteAddress(req);
    setCorsHeaders(req, res);
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const url = new URL(req.url ?? "/", baseUrl);
    if (req.method === "GET" && url.pathname === "/health") {
      sendJson(res, 200, { status: "ok" });
      return;
    }

    if (req.method === "GET" && url.pathname === "/status") {
      sendJson(res, 200, {
        status: "ok",
        bindHost: host,
        tokenRequired: true,
        shimEventsQueued: shimEvents.length,
        consentActionsQueued: consentActions.length,
        watcherCollectionEnabled: watcherRegistry.isCollectionEnabled(),
        watcherCounts: watcherRegistry.counts(),
        uploadQueue: sessionUploadQueue.stats()
      });
      return;
    }

    if (req.method === "GET" && url.pathname === "/auth/token") {
      assertTokenDisclosureOrigin(req);
      const token = createOAuthStartToken();
      sendJson(res, 200, { status: "ok", token, scope: "oauth_start", expiresInSeconds: 60 });
      return;
    }

    if (req.method === "GET" && url.pathname === "/oauth/status") {
      const provider = readProvider(url.searchParams.get("provider"));
      sendJson(res, 200, stateFor(provider));
      return;
    }

    if (req.method === "POST" && url.pathname === "/oauth/start") {
      assertRateLimit(req, url.pathname);
      assertOAuthStartToken(req);
      const body = await readJson(req);
      const provider = readProvider(body.provider);
      await startOAuth(provider);
      sendJson(res, 202, stateFor(provider));
      return;
    }

    if (req.method === "POST" && url.pathname === "/shim/events") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      const body = await readJson(req);
      recordShimEvent(body);
      sendJson(res, 202, { status: "accepted" });
      return;
    }

    if (req.method === "GET" && url.pathname === "/shim/events/status") {
      const lastEvent = shimEvents.at(-1);
      sendJson(res, 200, {
        queued: shimEvents.length,
        lastEventType: lastEvent?.type ?? null,
        lastProvider: lastEvent?.provider ?? null
      });
      return;
    }

    if (req.method === "GET" && url.pathname === "/host/processes") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      sendJson(res, 200, await readHostProcessSnapshot());
      return;
    }

    if (req.method === "GET" && url.pathname === "/adapters/codex-app/status") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      sendJson(res, 200, await readCodexAppPresence());
      return;
    }

    if (req.method === "GET" && url.pathname === "/watchers/status") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      sendJson(res, 200, {
        status: "ok",
        collectionEnabled: watcherRegistry.isCollectionEnabled(),
        uploadQueue: sessionUploadQueue.stats(),
        watcherCounts: watcherRegistry.counts(),
        watchers: watcherRegistry.list()
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/watchers/repositories") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      const body = await readJson(req);
      const watcher = updateWatcherRepository(body);
      if (body?.status !== "approved") {
        await sessionUploadQueue.cancelRepository(watcher.repoIdentityHash);
      }
      sendJson(res, 200, {
        status: "ok",
        collectionEnabled: watcherRegistry.isCollectionEnabled(),
        uploadQueue: sessionUploadQueue.stats(),
        watcher
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/watchers/settings") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      const body = await readJson(req);
      const enabled = body.enabled === true;
      const result = watcherRegistry.setCollectionEnabled(enabled);
      sendJson(res, 200, {
        status: "ok",
        collectionEnabled: result.collectionEnabled,
        uploadQueue: sessionUploadQueue.stats(),
        watcherCounts: watcherRegistry.counts(),
        watchers: result.watchers
      });
      return;
    }

    if (req.method === "GET" && url.pathname === "/consent/status") {
      sendJson(res, 200, {
        status: "ok",
        queuedActions: consentActions.length
      });
      return;
    }

    if (req.method === "POST" && url.pathname === "/consent/revoke") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      const body = await readJson(req);
      await recordConsentAction("revoke", body);
      sendJson(res, 202, { status: "accepted" });
      return;
    }

    if (req.method === "POST" && url.pathname === "/consent/purge-raw") {
      assertRateLimit(req, url.pathname);
      assertLocalApiToken(req);
      const body = await readJson(req);
      await recordConsentAction("purge_raw", body);
      sendJson(res, 202, { status: "accepted" });
      return;
    }

    sendJson(res, 404, { status: "failed", message: "Not found" });
  } catch (error) {
    setCorsHeaders(req, res);
    sendJson(res, error.status || 500, {
      status: "failed",
      message: error.status ? error.message : "Local OAuth companion failed"
    });
  }
});

server.listen(port, host, () => {
  console.log(`LearnLoop local AI companion listening on ${baseUrl}`);
});

process.once("SIGTERM", () => {
  watcherRegistry.stopAll();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1000).unref();
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
  res.setHeader("access-control-allow-headers", "content-type,x-learnloop-local-token,authorization");
  res.setHeader("access-control-allow-private-network", "true");
  res.setHeader("cache-control", "no-store");
  res.setHeader("x-content-type-options", "nosniff");
}

function assertAllowedHost(req) {
  const hostHeader = req.headers.host;
  if (!hostHeader) throw httpError(400, "Host header is required");
  const parsed = parseHostHeader(hostHeader);
  if (!parsed || !isLoopbackHost(parsed.hostname)) {
    throw httpError(403, "Host is not allowed");
  }
  if (parsed.port !== null && parsed.port !== port) {
    throw httpError(403, "Host port is not allowed");
  }
}

function assertAllowedOrigin(req) {
  const origin = req.headers.origin;
  if (origin && !allowedOrigins.has(origin)) {
    throw httpError(403, "Origin is not allowed");
  }
}

function assertTokenDisclosureOrigin(req) {
  const origin = req.headers.origin;
  if (!origin || !allowedOrigins.has(origin)) {
    throw httpError(403, "Origin is required for local token disclosure");
  }
}

function assertLoopbackRemoteAddress(req) {
  const address = req.socket.remoteAddress;
  if (address && !isLoopbackAddress(address)) {
    throw httpError(403, "Remote address is not allowed");
  }
}

function assertLocalApiToken(req) {
  const supplied = readTokenHeader(req);
  if (!supplied || !safeTokenEquals(supplied, localApiToken)) {
    throw httpError(401, "Local API token is required");
  }
}

function assertOAuthStartToken(req) {
  const supplied = readTokenHeader(req);
  if (supplied && safeTokenEquals(supplied, localApiToken)) return;
  if (consumeOAuthStartToken(supplied)) return;
  throw httpError(401, "Local OAuth start token is required");
}

function assertRateLimit(req, route) {
  const now = Date.now();
  const windowMs = 60_000;
  const limit = 120;
  const key = `${req.socket.remoteAddress ?? "unknown"}:${route}`;
  const bucket = rateLimitBuckets.get(key) ?? { count: 0, resetAt: now + windowMs };
  if (bucket.resetAt <= now) {
    bucket.count = 0;
    bucket.resetAt = now + windowMs;
  }
  bucket.count += 1;
  rateLimitBuckets.set(key, bucket);
  if (bucket.count > limit) {
    throw httpError(429, "Too many requests");
  }
}

function readTokenHeader(req) {
  const direct = req.headers["x-learnloop-local-token"];
  if (typeof direct === "string") return direct;
  const auth = req.headers.authorization;
  if (typeof auth === "string" && auth.toLowerCase().startsWith("bearer ")) {
    return auth.slice("bearer ".length).trim();
  }
  return "";
}

function createOAuthStartToken() {
  const token = randomBytes(32).toString("hex");
  oauthStartTokens.set(token, Date.now() + 60_000);
  pruneOAuthStartTokens();
  return token;
}

function consumeOAuthStartToken(token) {
  if (!token) return false;
  const expiresAt = oauthStartTokens.get(token);
  oauthStartTokens.delete(token);
  return typeof expiresAt === "number" && expiresAt > Date.now();
}

function pruneOAuthStartTokens() {
  const now = Date.now();
  for (const [token, expiresAt] of oauthStartTokens.entries()) {
    if (expiresAt <= now) oauthStartTokens.delete(token);
  }
}

function safeTokenEquals(left, right) {
  const leftBuffer = Buffer.from(left);
  const rightBuffer = Buffer.from(right);
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
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

function recordShimEvent(value) {
  const event = sanitizeShimEvent(value);
  shimEvents.push(event);
  if (shimEvents.length > maxShimEvents) {
    shimEvents.splice(0, shimEvents.length - maxShimEvents);
  }
}

async function recordConsentAction(action, value) {
  const input = typeof value === "object" && value !== null ? value : {};
  const repoIdentityHash = safeString(input.repoIdentityHash, 128);
  consentActions.push({
    action,
    repoIdentityHash,
    repositoryDisplayLabel: safeString(input.repositoryDisplayLabel, 120),
    requestedAt: new Date().toISOString()
  });
  if (consentActions.length > 100) {
    consentActions.splice(0, consentActions.length - 100);
  }
  if (action === "revoke" && repoIdentityHash) {
    await sessionUploadQueue.cancelRepository(repoIdentityHash);
  }
}

function updateWatcherRepository(value) {
  try {
    return watcherRegistry.updateRepository(value);
  } catch (error) {
    throw httpError(400, error.message || "watcher repository update is invalid");
  }
}

function sanitizeShimEvent(value) {
  const input = typeof value === "object" && value !== null ? value : {};
  return {
    type: safeString(input.type, 40),
    provider: safeString(input.provider, 40),
    invocationId: safeString(input.invocationId, 80),
    command: safeString(input.command, 40),
    argvCount: safeNumber(input.argvCount),
    startedAt: safeString(input.startedAt, 40),
    endedAt: safeString(input.endedAt, 40),
    durationMs: safeNumber(input.durationMs),
    exitCode: safeNumber(input.exitCode),
    signal: safeString(input.signal, 20),
    stdoutBytes: safeNumber(input.stdoutBytes),
    stderrBytes: safeNumber(input.stderrBytes),
    stdoutSuppressed: input.stdoutSuppressed === true,
    stderrSuppressed: input.stderrSuppressed === true
  };
}

function safeString(value, maxLength) {
  return typeof value === "string" ? value.slice(0, maxLength) : null;
}

function safeNumber(value) {
  return Number.isFinite(value) ? value : null;
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

function readLoopbackHost() {
  const configured = process.env.LEARNLOOP_LOCAL_AI_HOST || "127.0.0.1";
  if (!isLoopbackHost(configured)) {
    throw new Error("LEARNLOOP_LOCAL_AI_HOST must be 127.0.0.1, localhost, or ::1");
  }
  return configured === "localhost" ? "127.0.0.1" : configured;
}

function isLoopbackHost(value) {
  const host = String(value).replace(/^\[|\]$/g, "").toLowerCase();
  return host === "localhost" || isLoopbackAddress(host);
}

function isLoopbackAddress(value) {
  const address = String(value).replace(/^::ffff:/i, "").replace(/^\[|\]$/g, "").toLowerCase();
  if (address === "::1") return true;
  if (!/^127(?:\.\d{1,3}){3}$/.test(address)) return false;
  return address.split(".").every((part) => Number(part) >= 0 && Number(part) <= 255);
}

function parseHostHeader(value) {
  const raw = String(value).trim();
  if (raw.startsWith("[")) {
    const closing = raw.indexOf("]");
    if (closing < 0) return null;
    const hostname = raw.slice(1, closing);
    const portPart = raw.slice(closing + 1);
    return { hostname, port: portPart.startsWith(":") ? Number(portPart.slice(1)) : null };
  }
  const parts = raw.split(":");
  if (parts.length > 2) return null;
  return { hostname: parts[0], port: parts[1] === undefined ? null : Number(parts[1]) };
}

function localBaseUrl(hostname, listenPort) {
  const formattedHost = hostname === "::1" ? "[::1]" : hostname;
  return `http://${formattedHost}:${listenPort}`;
}

function readCsvEnv(name) {
  return (process.env[name] ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

function readDefaultAllowedOrigins() {
  const webPort = process.env.AI_CODE_WEB_PORT || "8080";
  const origins = [`http://localhost:${webPort}`, `http://127.0.0.1:${webPort}`];
  if (process.env.LEARNLOOP_LOCAL_AI_ALLOW_DEV_ORIGINS === "1") {
    origins.push("http://localhost:5173", "http://127.0.0.1:5173");
  }
  return origins.map(normalizeLoopbackOrigin);
}

function readAdditionalAllowedOrigins() {
  return readCsvEnv("LEARNLOOP_LOCAL_AI_ALLOWED_ORIGINS").map(normalizeLoopbackOrigin);
}

function readNumericEnv(name) {
  const configured = process.env[name]?.trim();
  if (!configured) return undefined;
  const parsed = Number(configured);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be a number`);
  }
  return parsed;
}

function normalizeLoopbackOrigin(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("LEARNLOOP_LOCAL_AI_ALLOWED_ORIGINS must contain valid loopback origins");
  }
  if (
    (url.protocol !== "http:" && url.protocol !== "https:") ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash ||
    !isLoopbackHost(url.hostname)
  ) {
    throw new Error("LEARNLOOP_LOCAL_AI_ALLOWED_ORIGINS may only contain loopback app origins");
  }
  return url.origin;
}

function resolveLocalApiConfigDir(configuredConfigDir) {
  if (!configuredConfigDir) return DEFAULT_CONFIG_DIR;
  if (!path.isAbsolute(configuredConfigDir)) {
    throw new Error("LEARNLOOP_LOCAL_AI_CONFIG_DIR must be an absolute path");
  }
  return path.resolve(configuredConfigDir);
}

function resolveLocalApiTokenFile(configuredTokenFile, directory) {
  if (!configuredTokenFile) return path.join(directory, DEFAULT_TOKEN_FILE_NAME);
  if (!path.isAbsolute(configuredTokenFile)) {
    throw new Error("LEARNLOOP_LOCAL_AI_TOKEN_FILE must be an absolute path inside the LearnLoop config directory");
  }
  const resolved = path.resolve(configuredTokenFile);
  if (!isPathInside(resolved, directory)) {
    throw new Error("LEARNLOOP_LOCAL_AI_TOKEN_FILE must be inside the LearnLoop config directory");
  }
  return resolved;
}

function isPathInside(candidate, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (relative.length > 0 && !relative.startsWith("..") && !path.isAbsolute(relative));
}

async function ensureLocalApiToken(filePath, options = {}) {
  const directory = path.dirname(filePath);
  await ensureLocalApiTokenDirectory(directory, options);

  try {
    const existing = await lstat(filePath);
    if (existing.isSymbolicLink() || !existing.isFile()) {
      throw new Error(`Refusing to use unsafe local API token file: ${filePath}`);
    }
    const content = (await readFile(filePath, "utf8")).trim();
    if (content.length >= 32) {
      await chmod(filePath, 0o600);
      return content;
    }
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }

  const token = randomBytes(32).toString("hex");
  const flags = fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | (fsConstants.O_NOFOLLOW ?? 0);
  const handle = await open(filePath, flags, 0o600);
  try {
    await handle.writeFile(`${token}\n`);
  } finally {
    await handle.close();
  }
  await chmod(filePath, 0o600);
  const tokenStats = await stat(filePath);
  if (!tokenStats.isFile()) {
    throw new Error(`Local API token path is not a file: ${filePath}`);
  }
  return token;
}

async function ensureLocalApiTokenDirectory(directory, options) {
  if (isPathInside(directory, process.cwd()) || (await hasGitAncestor(directory))) {
    throw new Error("LearnLoop local API token directory must be outside application and repository directories");
  }
  if (!options.requireManagedDirectory) {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    await assertSafeDirectory(directory);
    await chmod(directory, 0o700);
    return;
  }

  let existed = true;
  try {
    await assertSafeDirectory(directory);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    existed = false;
    await mkdir(directory, { recursive: true, mode: 0o700 });
    await assertSafeDirectory(directory);
  }

  const markerPath = path.join(directory, TOKEN_DIRECTORY_MARKER);
  if (existed) {
    const marker = await lstat(markerPath).catch((error) => {
      if (error?.code === "ENOENT") {
        throw new Error(`LEARNLOOP_LOCAL_AI_TOKEN_FILE parent must be a LearnLoop-managed directory: ${directory}`);
      }
      throw error;
    });
    if (marker.isSymbolicLink() || !marker.isFile()) {
      throw new Error(`Unsafe LearnLoop token directory marker: ${markerPath}`);
    }
  } else {
    await writeFile(markerPath, "LearnLoop local API token directory\n", { mode: 0o600, flag: "wx" });
  }
  await chmod(directory, 0o700);
}

async function assertSafeDirectory(directory) {
  const directoryStats = await lstat(directory);
  if (directoryStats.isSymbolicLink() || !directoryStats.isDirectory()) {
    throw new Error(`Refusing to use unsafe local API token directory: ${directory}`);
  }
}

async function hasGitAncestor(directory) {
  let current = path.resolve(directory);
  while (true) {
    const gitStats = await lstat(path.join(current, ".git")).catch(() => null);
    if (gitStats !== null) return true;
    const parent = path.dirname(current);
    if (parent === current) return false;
    current = parent;
  }
}
