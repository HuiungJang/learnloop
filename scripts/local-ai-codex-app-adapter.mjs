import { readHostProcessSnapshot } from "./local-ai-process-snapshot.mjs";

const PROVIDER = "codex_app";

export async function readCodexAppPresence(options = {}) {
  const snapshot = options.snapshot ?? (await readHostProcessSnapshot(options));
  return detectCodexAppPresence(snapshot, options);
}

export function detectCodexAppPresence(snapshot, options = {}) {
  const capturedAt = typeof snapshot?.capturedAt === "string" ? snapshot.capturedAt : new Date(options.now ?? Date.now()).toISOString();
  const snapshotStatus = typeof snapshot?.status === "string" ? snapshot.status : "unavailable";
  const processes = Array.isArray(snapshot?.processes) ? snapshot.processes : [];
  const matches = processes.filter(isCodexAppProcess).map(sanitizeProcess);

  if (matches.length === 0) {
    return {
      provider: PROVIDER,
      status: "unavailable",
      detectedAt: capturedAt,
      snapshotStatus,
      reason: snapshotStatus === "ok" ? "process_not_found" : snapshot?.reason ?? "process_snapshot_unavailable",
      matchedProcesses: []
    };
  }

  return {
    provider: PROVIDER,
    status: codexStatus(matches, options),
    detectedAt: capturedAt,
    snapshotStatus,
    reason: null,
    matchedProcesses: matches
  };
}

function codexStatus(matches, options) {
  const recentProcessIds = new Set(options.recentProcessIds ?? []);
  if (matches.some((processInfo) => processInfo.frontmost === true)) return "frontmost";
  if (matches.some((processInfo) => processInfo.appActive === true || recentProcessIds.has(processInfo.pid))) return "recently_active";
  return "running";
}

function isCodexAppProcess(processInfo) {
  const processName = processInfo.processName ?? "";
  const executablePath = processInfo.executablePath ?? "";
  if (executablePath.includes("/Codex.app/") || executablePath.endsWith("/Codex.app")) return true;
  return processName === "Codex" || processName.startsWith("Codex Helper");
}

function sanitizeProcess(processInfo) {
  return {
    pid: processInfo.pid ?? null,
    parentPid: processInfo.parentPid ?? null,
    processName: processInfo.processName ?? null,
    executablePath: processInfo.executablePath ?? null,
    frontmost: typeof processInfo.frontmost === "boolean" ? processInfo.frontmost : null,
    appActive: typeof processInfo.appActive === "boolean" ? processInfo.appActive : null
  };
}
