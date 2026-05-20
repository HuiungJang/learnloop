#!/usr/bin/env node
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { accessSync, statSync } from "node:fs";
import { constants as fsConstants } from "node:fs";
import { access, chmod, lstat, mkdir, open, readdir, readFile, realpath, rm, stat } from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const LEGACY_CODEX_SHIM_MARKER = "LEARNLOOP_CODEX_SHIM";
const GENERIC_SHIM_MARKER = "LEARNLOOP_LOCAL_AI_SHIM";
const DEFAULT_PROVIDER = "codex";
const DEFAULT_EVENT_TIMEOUT_MS = 20;
const FORWARDED_SIGNALS = ["SIGHUP", "SIGINT", "SIGTERM", "SIGQUIT"];
const MANAGED_DIRECTORY_MARKER = ".learnloop-managed";
const DEFAULT_LOCAL_API_CONFIG_DIR = path.join(os.homedir(), ".learnloop");
const DEFAULT_LOCAL_API_TOKEN_FILE_NAME = "local-api-token";

const providerConfigs = {
  codex: {
    command: "codex",
    label: "Codex CLI",
    eventProvider: "codex_cli"
  },
  gemini: {
    command: "gemini",
    label: "Gemini CLI",
    eventProvider: "gemini_cli"
  },
  claude: {
    command: "claude",
    label: "Claude Code",
    eventProvider: "claude_code"
  }
};

export async function installCodexShim(options = {}) {
  return await installProviderShim(DEFAULT_PROVIDER, options);
}

export async function installProviderShim(providerName, options = {}) {
  const provider = providerConfig(providerName);
  const paths = shimPaths(provider.command, options);
  await ensureShimStorage(paths);

  const resolved = await resolveOriginalCommand(provider.command, {
    pathEnv: options.pathEnv,
    shimDir: paths.shimDir
  });
  if (!resolved.originalPath) {
    throw new Error(
      resolved.recursiveCandidateFound
        ? `Original ${provider.command} resolves to a LearnLoop shim`
        : `Original ${provider.command} was not found in PATH`
    );
  }

  const originalHash = await hashFileIfReadable(resolved.originalPath);
  const metadata = {
    provider: provider.name,
    command: provider.command,
    label: provider.label,
    originalPath: resolved.originalPath,
    originalHash,
    shimPath: paths.shimPath,
    installedAt: new Date().toISOString(),
    installedBy: "learnloop-local-ai-shim",
    scriptPath: scriptPath(options),
    nodePath: options.nodePath ?? process.execPath
  };

  await writeManagedFile(paths.shimPath, renderShimScript(metadata), 0o700, { requireLearnLoopShimMarker: true });
  await writeMetadata(paths.metadataPath, metadata);
  return {
    ...metadata,
    active: isFirstCommandPath(provider.command, paths.shimPath, options.pathEnv),
    pathGuidance: `Put ${paths.shimDir} before the real ${provider.command} directory in PATH.`
  };
}

export async function uninstallCodexShim(options = {}) {
  return await uninstallProviderShim(DEFAULT_PROVIDER, options);
}

export async function uninstallProviderShim(providerName, options = {}) {
  const provider = providerConfig(providerName);
  const paths = shimPaths(provider.command, options);
  await removeManagedFile(paths.shimPath, { requireLearnLoopShimMarker: true });
  await removeManagedFile(paths.metadataPath);
  return { provider: provider.name, shimPath: paths.shimPath, removed: true };
}

export async function repairCodexShim(options = {}) {
  return await repairProviderShim(DEFAULT_PROVIDER, options);
}

export async function repairProviderShim(providerName, options = {}) {
  const provider = providerConfig(providerName);
  const paths = shimPaths(provider.command, options);
  const metadata = await readMetadata(paths.metadataPath);
  const resolved = await resolveOriginalCommand(provider.command, {
    pathEnv: options.pathEnv,
    shimDir: paths.shimDir
  });
  const originalPath = resolved.originalPath ?? metadata?.originalPath;
  if (!originalPath || (await isLearnLoopShim(originalPath))) {
    throw new Error(`Original ${provider.command} could not be repaired`);
  }
  await access(originalPath, fsConstants.X_OK);
  const originalHash = await hashFileIfReadable(originalPath);
  const repaired = {
    provider: provider.name,
    command: provider.command,
    label: provider.label,
    originalPath,
    originalHash,
    shimPath: paths.shimPath,
    installedAt: metadata?.installedAt ?? new Date().toISOString(),
    repairedAt: new Date().toISOString(),
    installedBy: "learnloop-local-ai-shim",
    scriptPath: scriptPath(options),
    nodePath: options.nodePath ?? process.execPath
  };
  await ensureShimStorage(paths);
  await writeManagedFile(paths.shimPath, renderShimScript(repaired), 0o700, { requireLearnLoopShimMarker: true });
  await writeMetadata(paths.metadataPath, repaired);
  return {
    ...repaired,
    active: isFirstCommandPath(provider.command, paths.shimPath, options.pathEnv),
    pathGuidance: `Put ${paths.shimDir} before the real ${provider.command} directory in PATH.`
  };
}

export async function statusCodexShim(options = {}) {
  return await statusProviderShim(DEFAULT_PROVIDER, options);
}

export async function statusProviderShim(providerName, options = {}) {
  const provider = providerConfig(providerName);
  const paths = shimPaths(provider.command, options);
  const metadata = await readMetadata(paths.metadataPath);
  const shimExists = await isExecutable(paths.shimPath);
  const active = shimExists && isFirstCommandPath(provider.command, paths.shimPath, options.pathEnv);
  const warnings = [];
  const problems = [];

  if (!metadata) {
    problems.push("not_installed");
  }
  if (!shimExists) {
    problems.push("shim_missing");
  }
  if (shimExists && !active) {
    problems.push("path_precedence");
  }

  if (metadata?.originalPath) {
    if (!(await isExecutable(metadata.originalPath))) {
      problems.push("original_missing");
    } else {
      const currentHash = await hashFileIfReadable(metadata.originalPath);
      if (metadata.originalHash && currentHash && metadata.originalHash !== currentHash) {
        warnings.push("original_hash_changed");
      }
    }

    const resolved = await resolveOriginalCommand(provider.command, {
      pathEnv: options.pathEnv,
      shimDir: paths.shimDir
    });
    if (resolved.originalPath && resolved.originalPath !== metadata.originalPath) {
      warnings.push("original_path_changed");
    }
  }

  return {
    provider: provider.name,
    shimDir: paths.shimDir,
    shimPath: paths.shimPath,
    installed: metadata !== null && shimExists,
    active,
    metadata,
    warnings,
    problems,
    pathGuidance: `Put ${paths.shimDir} before the real ${provider.command} directory in PATH.`
  };
}

export async function runProviderShim(providerName, args, options = {}) {
  const provider = providerConfig(providerName);
  const paths = shimPaths(provider.command, options);
  const metadata = await readMetadata(paths.metadataPath);
  const resolved =
    metadata?.originalPath && (await isExecutable(metadata.originalPath))
      ? { originalPath: metadata.originalPath }
      : await resolveOriginalCommand(provider.command, {
          pathEnv: options.pathEnv,
          shimDir: paths.shimDir
        });

  if (!resolved.originalPath) {
    throw new Error(`Original ${provider.command} was not found`);
  }

  return await forwardProviderRuntime(providerName, args, { ...options, originalPath: resolved.originalPath });
}

export async function forwardProviderRuntime(providerName, args, options = {}) {
  const provider = providerConfig(providerName);
  const originalPath = options.originalPath;
  if (!originalPath) {
    throw new Error(`Original ${provider.command} was not found`);
  }

  const invocationId = options.invocationId ?? randomUUID();
  const startedAt = new Date();
  const customEventSender = options.eventSender;
  const stdout = options.stdout ?? process.stdout;
  const stderr = options.stderr ?? process.stderr;
  const output = createOutputCollector();
  const cwd = options.cwd ?? process.cwd();
  const stdin = options.stdin ?? "inherit";
  const captureOutput = shouldCaptureProviderOutput({
    stdin: stdin === "inherit" ? process.stdin : stdin,
    stdout,
    stderr,
    forceCapture: options.captureOutput
  });

  safeEmit(customEventSender ?? ((event) => sendCompanionEvent(event, { unref: true })), {
    type: "shim_start",
    provider: provider.eventProvider,
    invocationId,
    cwd,
    command: provider.command,
    argvCount: args.length,
    startedAt: startedAt.toISOString()
  });

  return await new Promise((resolve, reject) => {
    const child = spawn(originalPath, args, {
      cwd,
      env: options.env ?? process.env,
      stdio: [stdin, captureOutput ? "pipe" : "inherit", captureOutput ? "pipe" : "inherit"]
    });
    const signalHandlers = new Map();

    child.stdout?.on("data", (chunk) => {
      output.pushStdout(chunk);
      stdout.write(chunk);
    });
    child.stderr?.on("data", (chunk) => {
      output.pushStderr(chunk);
      stderr.write(chunk);
    });
    child.on("error", (error) => {
      cleanupSignals(signalHandlers);
      reject(error);
    });
    child.on("close", (code, signal) => {
      cleanupSignals(signalHandlers);
      const endedAt = new Date();
      flushEvent(customEventSender ?? ((event) => sendCompanionEvent(event, { unref: false })), buildEndEvent({
        provider: provider.eventProvider,
        invocationId,
        cwd,
        command: provider.command,
        startedAt,
        endedAt,
        code,
        signal,
        output
      }), options.eventTimeoutMs).finally(() => {
        resolve({ code, signal, exitCode: code ?? signalExitCode(signal) });
      });
    });

    for (const signalName of FORWARDED_SIGNALS) {
      const handler = () => {
        if (child.exitCode === null && child.signalCode === null) {
          child.kill(signalName);
        }
      };
      signalHandlers.set(signalName, handler);
      process.once(signalName, handler);
    }
  });
}

export function shouldCaptureProviderOutput({ stdin = process.stdin, stdout = process.stdout, stderr = process.stderr, forceCapture } = {}) {
  if (forceCapture !== undefined) return Boolean(forceCapture);
  return !(isTtyLike(stdin) && isTtyLike(stdout) && isTtyLike(stderr));
}

function isTtyLike(stream) {
  return typeof stream === "object" && stream !== null && stream.isTTY === true;
}

export function buildEndEvent({ provider, invocationId, cwd, command, startedAt, endedAt, code, signal, output }) {
  const snapshot = output.snapshot();
  return {
    type: "shim_end",
    provider,
    invocationId,
    cwd,
    command,
    endedAt: endedAt.toISOString(),
    durationMs: Math.max(0, endedAt.getTime() - startedAt.getTime()),
    exitCode: code,
    signal,
    stdoutBytes: snapshot.stdoutBytes,
    stderrBytes: snapshot.stderrBytes,
    stdoutExcerpt: null,
    stdoutSuppressed: snapshot.stdoutBytes > 0,
    stderrExcerpt: null,
    stderrSuppressed: snapshot.stderrBytes > 0,
    excerptLimitBytes: 0
  };
}

export function createOutputCollector() {
  let stdoutBytes = 0;
  let stderrBytes = 0;

  return {
    pushStdout(chunk) {
      stdoutBytes += chunk.length;
    },
    pushStderr(chunk) {
      stderrBytes += chunk.length;
    },
    snapshot() {
      return { stdoutBytes, stderrBytes };
    }
  };
}

export async function sendCompanionEvent(event, options = {}) {
  const baseUrl = options.baseUrl ?? process.env.LEARNLOOP_LOCAL_AI_COMPANION_URL ?? defaultCompanionBaseUrl();
  const timeoutMs = Number(options.timeoutMs ?? process.env.LEARNLOOP_SHIM_EVENT_TIMEOUT_MS ?? DEFAULT_EVENT_TIMEOUT_MS);
  const payload = `${JSON.stringify(event)}\n`;
  const url = companionEventUrl(baseUrl);
  if (url === null) return Promise.resolve();
  const client = url.protocol === "https:" ? https : http;
  const token = options.token ?? (await readLocalApiToken(options.tokenFile));

  return new Promise((resolve) => {
    const request = client.request(
      url,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(payload),
          ...(token ? { "x-learnloop-local-token": token } : {})
        },
        timeout: timeoutMs
      },
      (response) => {
        response.resume();
        response.on("end", resolve);
      }
    );
    request.on("timeout", () => request.destroy());
    request.on("error", resolve);
    request.end(payload);
    if (options.unref !== false) {
      request.unref?.();
    }
  });
}

export function isLoopbackCompanionUrl(value) {
  return companionEventUrl(value) !== null;
}

function companionEventUrl(baseUrl) {
  try {
    const url = new URL("/shim/events", baseUrl);
    return isLoopbackHost(url.hostname) ? url : null;
  } catch {
    return null;
  }
}

function isLoopbackHost(hostname) {
  const host = String(hostname).replace(/^\[|\]$/g, "").toLowerCase();
  if (host === "localhost" || host === "::1") return true;
  const match = /^127(?:\.(\d{1,3})){3}$/.exec(host);
  if (!match) return false;
  return host.split(".").every((part) => Number(part) >= 0 && Number(part) <= 255);
}

async function readLocalApiToken(tokenFile = resolveLocalApiTokenFile(process.env.LEARNLOOP_LOCAL_AI_TOKEN_FILE)) {
  if (!tokenFile) return "";
  try {
    const tokenStats = await lstat(tokenFile);
    if (tokenStats.isSymbolicLink() || !tokenStats.isFile()) return "";
    return (await readFile(tokenFile, "utf8")).trim();
  } catch {
    return "";
  }
}

function resolveLocalApiTokenFile(configuredTokenFile) {
  const configDir = resolveLocalApiConfigDir(process.env.LEARNLOOP_LOCAL_AI_CONFIG_DIR);
  if (!configDir || isPathInside(configDir, process.cwd())) return null;
  const trimmed = configuredTokenFile?.trim();
  if (!trimmed) return path.join(configDir, DEFAULT_LOCAL_API_TOKEN_FILE_NAME);
  if (!path.isAbsolute(trimmed)) return null;
  const resolved = path.resolve(trimmed);
  if (!isPathInside(resolved, configDir) || isPathInside(resolved, process.cwd())) return null;
  return resolved;
}

function resolveLocalApiConfigDir(configuredConfigDir) {
  const trimmed = configuredConfigDir?.trim();
  if (!trimmed) return DEFAULT_LOCAL_API_CONFIG_DIR;
  if (!path.isAbsolute(trimmed)) return null;
  return path.resolve(trimmed);
}

function defaultCompanionBaseUrl() {
  const host = process.env.LEARNLOOP_LOCAL_AI_HOST || "127.0.0.1";
  const port = process.env.LEARNLOOP_LOCAL_AI_PORT || 4317;
  const formattedHost = host === "::1" ? "[::1]" : host;
  return `http://${formattedHost}:${port}`;
}

function isPathInside(candidate, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (relative.length > 0 && !relative.startsWith("..") && !path.isAbsolute(relative));
}

function safeEmit(eventSender, event) {
  try {
    Promise.resolve(eventSender(event)).catch(() => {});
  } catch {
    // Collection must never affect the provider CLI.
  }
}

function flushEvent(eventSender, event, timeoutMs = DEFAULT_EVENT_TIMEOUT_MS) {
  return new Promise((resolve) => {
    let settled = false;
    const timer = setTimeout(finish, timeoutMs);
    function finish() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve();
    }
    try {
      Promise.resolve(eventSender(event)).then(finish, finish);
    } catch {
      finish();
    }
  });
}

export async function resolveOriginalCommand(command, options = {}) {
  const shimDir = path.resolve(options.shimDir ?? defaultShimDir());
  const pathEntries = splitPath(options.pathEnv ?? process.env.PATH ?? "");
  let recursiveCandidateFound = false;

  for (const entry of pathEntries) {
    const candidate = path.resolve(entry, command);
    if (!(await isExecutable(candidate))) continue;
    const realCandidate = await realpath(candidate).catch(() => candidate);
    const realShimDir = await realpath(shimDir).catch(() => shimDir);
    if (realCandidate.startsWith(`${realShimDir}${path.sep}`) || realCandidate === path.join(realShimDir, command)) {
      recursiveCandidateFound = true;
      continue;
    }
    if (await isLearnLoopShim(candidate)) {
      recursiveCandidateFound = true;
      continue;
    }
    return { originalPath: realCandidate, recursiveCandidateFound };
  }

  return { originalPath: null, recursiveCandidateFound };
}

function renderShimScript(metadata) {
  return `#!/usr/bin/env sh
# ${shimMarker(metadata.provider)}
exec ${shellQuote(metadata.nodePath)} ${shellQuote(metadata.scriptPath)} run ${shellQuote(metadata.provider)} "$@"
`;
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, "'\\''")}'`;
}

function providerConfig(providerName) {
  const config = providerConfigs[providerName];
  if (!config) throw new Error(`Unsupported shim provider: ${providerName}`);
  return { name: providerName, ...config };
}

function shimMarker(providerName) {
  return providerName === DEFAULT_PROVIDER ? LEGACY_CODEX_SHIM_MARKER : GENERIC_SHIM_MARKER;
}

function shimPaths(command, options = {}) {
  const shimDir = path.resolve(options.shimDir ?? defaultShimDir());
  const metadataDir = path.join(shimDir, ".metadata");
  return {
    shimDir,
    metadataDir,
    shimPath: path.join(shimDir, command),
    metadataPath: path.join(metadataDir, `${command}.json`)
  };
}

async function ensureShimStorage(paths) {
  await ensureManagedDirectory(paths.shimDir);
  await ensureManagedDirectory(paths.metadataDir);
}

async function ensureManagedDirectory(directoryPath) {
  try {
    const existing = await lstat(directoryPath);
    if (existing.isSymbolicLink() || !existing.isDirectory()) {
      throw new Error(`Refusing to use unsafe LearnLoop shim directory: ${directoryPath}`);
    }
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    await mkdir(directoryPath, { recursive: true, mode: 0o700 });
  }
  const existing = await lstat(directoryPath);
  if (existing.isSymbolicLink() || !existing.isDirectory()) {
    throw new Error(`Refusing to use unsafe LearnLoop shim directory: ${directoryPath}`);
  }
  const entries = await readdir(directoryPath);
  const hasMarker = entries.includes(MANAGED_DIRECTORY_MARKER);
  if (!hasMarker && entries.length > 0) {
    throw new Error(`Refusing to use non-empty unmanaged LearnLoop shim directory: ${directoryPath}`);
  }
  await writeManagedFile(path.join(directoryPath, MANAGED_DIRECTORY_MARKER), "learnloop local shim directory\n", 0o600);
  await chmod(directoryPath, 0o700);
}

async function writeManagedFile(filePath, content, mode, { requireLearnLoopShimMarker = false } = {}) {
  const existed = await validateManagedWriteTarget(filePath, { requireLearnLoopShimMarker });
  if (existed) {
    await rm(filePath);
  }
  const flags = fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | (fsConstants.O_NOFOLLOW ?? 0);
  const handle = await open(filePath, flags, mode);
  try {
    await handle.writeFile(content);
  } finally {
    await handle.close();
  }
  await chmod(filePath, mode);
}

async function validateManagedWriteTarget(filePath, { requireLearnLoopShimMarker = false } = {}) {
  try {
    const existing = await lstat(filePath);
    if (existing.isSymbolicLink() || !existing.isFile()) {
      throw new Error(`Refusing to overwrite unsafe LearnLoop shim file: ${filePath}`);
    }
    if (requireLearnLoopShimMarker && !(await isLearnLoopShim(filePath))) {
      throw new Error(`Refusing to overwrite non-LearnLoop shim file: ${filePath}`);
    }
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

async function removeManagedFile(filePath, { requireLearnLoopShimMarker = false } = {}) {
  const exists = await validateManagedWriteTarget(filePath, { requireLearnLoopShimMarker });
  if (exists) {
    await rm(filePath);
  }
}

function defaultShimDir() {
  return process.env.LEARNLOOP_SHIM_DIR ?? path.join(os.homedir(), ".learnloop", "shims");
}

function scriptPath(options = {}) {
  return options.scriptPath ?? fileURLToPath(import.meta.url);
}

function splitPath(pathEnv) {
  return String(pathEnv)
    .split(path.delimiter)
    .filter((entry) => entry.length > 0);
}

async function isExecutable(filePath) {
  try {
    await access(filePath, fsConstants.X_OK);
    const result = await stat(filePath);
    return result.isFile();
  } catch {
    return false;
  }
}

async function isLearnLoopShim(filePath) {
  try {
    const content = await readFile(filePath, "utf8");
    return content.includes(LEGACY_CODEX_SHIM_MARKER) || content.includes(GENERIC_SHIM_MARKER);
  } catch {
    return false;
  }
}

async function hashFileIfReadable(filePath) {
  try {
    const content = await readFile(filePath);
    return createHash("sha256").update(content).digest("hex");
  } catch {
    return null;
  }
}

async function readMetadata(metadataPath) {
  try {
    return JSON.parse(await readFile(metadataPath, "utf8"));
  } catch {
    return null;
  }
}

async function writeMetadata(metadataPath, metadata) {
  await writeManagedFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`, 0o600);
}

function isFirstCommandPath(command, expectedPath, pathEnv = process.env.PATH ?? "") {
  for (const entry of splitPath(pathEnv)) {
    const candidate = path.resolve(entry, command);
    if (!isExecutableSync(candidate)) continue;
    return path.resolve(candidate) === path.resolve(expectedPath);
  }
  return false;
}

function isExecutableSync(filePath) {
  try {
    accessSync(filePath, fsConstants.X_OK);
    return statSync(filePath).isFile();
  } catch {
    return false;
  }
}

function cleanupSignals(signalHandlers) {
  for (const [signalName, handler] of signalHandlers.entries()) {
    process.off(signalName, handler);
  }
}

function signalExitCode(signal) {
  if (!signal) return 1;
  const signalNumber = os.constants.signals?.[signal];
  return typeof signalNumber === "number" ? 128 + signalNumber : 1;
}

function printInstallResult(result) {
  const label = result.label ?? result.provider;
  console.log(`Installed ${label} shim: ${result.shimPath}`);
  console.log(`Original ${label}: ${result.originalPath}`);
  if (!result.active) {
    console.log(`PATH update needed: ${result.pathGuidance}`);
  }
}

function printStatus(result) {
  const label = result.metadata?.label ?? result.provider;
  console.log(result.installed ? `${label} shim installed: ${result.shimPath}` : `${label} shim is not installed.`);
  console.log(result.active ? `${label} shim is active in PATH.` : `${label} shim is not first in PATH.`);
  if (result.metadata?.originalPath) {
    console.log(`Original ${label}: ${result.metadata.originalPath}`);
  }
  for (const warning of result.warnings) {
    console.log(`Warning: ${warning}`);
  }
  for (const problem of result.problems) {
    console.log(`Problem: ${problem}`);
  }
  if (!result.active) {
    console.log(result.pathGuidance);
  }
}

async function main(argv) {
  const [command, providerOrAction, maybeAction, ...rest] = argv;
  try {
    if (command === "run") {
      const providerName = providerOrAction ?? DEFAULT_PROVIDER;
      const result = await runProviderShim(providerName, [maybeAction, ...rest].filter((value) => value !== undefined));
      process.exitCode = result.exitCode;
      return;
    }

    const providerName = command ?? DEFAULT_PROVIDER;
    const provider = providerConfig(providerName);
    const action = providerOrAction ?? "status";
    if (action === "install") {
      printInstallResult(await installProviderShim(providerName));
    } else if (action === "uninstall") {
      const result = await uninstallProviderShim(providerName);
      console.log(`Removed ${provider.label} shim: ${result.shimPath}`);
    } else if (action === "repair") {
      printInstallResult(await repairProviderShim(providerName));
    } else if (action === "status") {
      printStatus(await statusProviderShim(providerName));
    } else {
      throw new Error("Usage: local-ai-shim.mjs [codex|gemini|claude] [install|uninstall|repair|status]");
    }
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
