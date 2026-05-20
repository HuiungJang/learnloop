import { spawn } from "node:child_process";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_TIMEOUT_MS = 1_000;
const DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;
const DEFAULT_MAX_PROCESSES = 500;

export async function readHostProcessSnapshot(options = {}) {
  const platform = options.platform ?? process.platform;
  const capturedAt = new Date(options.now ?? Date.now()).toISOString();
  const homeDir = options.homeDir ?? os.homedir();

  if (options.snapshotReader) {
    const snapshot = await options.snapshotReader();
    return normalizeProcessSnapshot(snapshot, { platform, capturedAt, homeDir, maxProcesses: options.maxProcesses });
  }

  if (platform !== "darwin") {
    return {
      status: "unavailable",
      platform,
      capturedAt,
      reason: "unsupported_platform",
      processes: []
    };
  }

  return readMacosProcessSnapshot({
    ...options,
    capturedAt,
    homeDir
  });
}

export async function readMacosProcessSnapshot(options = {}) {
  const capturedAt = options.capturedAt ?? new Date(options.now ?? Date.now()).toISOString();
  const homeDir = options.homeDir ?? os.homedir();
  const commandRunner = options.commandRunner ?? runBoundedCommand;

  try {
    const result = await commandRunner("ps", ["-axo", "pid=,ppid=,comm="], {
      timeoutMs: options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
      maxOutputBytes: options.maxOutputBytes ?? DEFAULT_MAX_OUTPUT_BYTES
    });

    return {
      status: result.timedOut ? "degraded" : "ok",
      platform: "darwin",
      capturedAt,
      reason: result.timedOut ? "process_snapshot_timeout" : null,
      processes: parseMacosPsOutput(result.stdout, {
        homeDir,
        maxProcesses: options.maxProcesses ?? DEFAULT_MAX_PROCESSES
      })
    };
  } catch (error) {
    return {
      status: error.timedOut ? "degraded" : "unavailable",
      platform: "darwin",
      capturedAt,
      reason: error.timedOut ? "process_snapshot_timeout" : "process_snapshot_failed",
      processes: []
    };
  }
}

export function normalizeProcessSnapshot(snapshot, options = {}) {
  const value = typeof snapshot === "object" && snapshot !== null ? snapshot : {};
  const homeDir = options.homeDir ?? os.homedir();
  const maxProcesses = options.maxProcesses ?? DEFAULT_MAX_PROCESSES;
  const processes = Array.isArray(value.processes) ? value.processes : [];

  return {
    status: value.status === "degraded" || value.status === "unavailable" ? value.status : "ok",
    platform: typeof value.platform === "string" ? value.platform : options.platform ?? process.platform,
    capturedAt: typeof value.capturedAt === "string" ? value.capturedAt : options.capturedAt ?? new Date().toISOString(),
    reason: typeof value.reason === "string" ? value.reason : null,
    processes: processes.slice(0, maxProcesses).map((processInfo) => normalizeProcessInfo(processInfo, homeDir))
  };
}

export function parseMacosPsOutput(stdout, options = {}) {
  const homeDir = options.homeDir ?? os.homedir();
  const maxProcesses = options.maxProcesses ?? DEFAULT_MAX_PROCESSES;
  const processes = [];

  for (const line of String(stdout ?? "").split(/\r?\n/)) {
    if (processes.length >= maxProcesses) break;
    const match = line.match(/^\s*(\d+)\s+(\d+)\s+(.+?)\s*$/);
    if (!match) continue;

    const executablePath = match[3];
    processes.push(
      normalizeProcessInfo(
        {
          pid: Number(match[1]),
          parentPid: Number(match[2]),
          processName: path.basename(executablePath),
          executablePath,
          frontmost: null,
          appActive: null
        },
        homeDir
      )
    );
  }

  return processes;
}

function normalizeProcessInfo(processInfo, homeDir) {
  const value = typeof processInfo === "object" && processInfo !== null ? processInfo : {};
  return {
    pid: safePositiveInteger(value.pid),
    parentPid: safePositiveInteger(value.parentPid),
    processName: safeString(value.processName, 160),
    executablePath: redactHomePath(safeString(value.executablePath, 2_000), homeDir),
    frontmost: typeof value.frontmost === "boolean" ? value.frontmost : null,
    appActive: typeof value.appActive === "boolean" ? value.appActive : null
  };
}

function safePositiveInteger(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function safeString(value, maxLength) {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed.slice(0, maxLength);
}

function redactHomePath(value, homeDir) {
  if (!value || !homeDir) return value;
  if (!path.isAbsolute(value)) return value;
  const normalizedHome = path.resolve(homeDir);
  const normalizedValue = path.resolve(value);
  if (normalizedValue === normalizedHome) return "~";
  if (normalizedValue.startsWith(`${normalizedHome}${path.sep}`)) {
    return `~/${path.relative(normalizedHome, normalizedValue)}`;
  }
  return value;
}

function runBoundedCommand(command, args, options) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["ignore", "pipe", "ignore"] });
    const chunks = [];
    let outputBytes = 0;
    let settled = false;
    const timeout = setTimeout(() => {
      settled = true;
      child.kill("SIGTERM");
      const error = new Error("process snapshot command timed out");
      error.timedOut = true;
      reject(error);
    }, options.timeoutMs);

    child.stdout.on("data", (chunk) => {
      if (outputBytes >= options.maxOutputBytes) return;
      const remaining = options.maxOutputBytes - outputBytes;
      const next = chunk.length > remaining ? chunk.subarray(0, remaining) : chunk;
      chunks.push(next);
      outputBytes += next.length;
    });

    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    });

    child.on("close", () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve({ stdout: Buffer.concat(chunks).toString("utf8"), timedOut: false });
    });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  readHostProcessSnapshot()
    .then((snapshot) => {
      console.log(JSON.stringify(snapshot, null, 2));
    })
    .catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
}
