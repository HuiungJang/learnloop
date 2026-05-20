const DEFAULT_LOOKBACK_MS = 30_000;
const DEFAULT_DURATION_MS = 120_000;
const SINGLE_TOOL_CONFIDENCE = 0.55;
const COMPETING_TOOL_CONFIDENCE = 0.35;

export function correlateGuiActivityWindows(input = {}, options = {}) {
  const approvedRepoIds = new Set((input.approvedRepositories ?? []).map((repo) => repo.repoIdentityHash).filter(Boolean));
  const windows = (input.toolSignals ?? []).map((signal) => activityWindow(signal, options)).filter(Boolean);
  const correlations = [];
  const ignoredChanges = [];

  for (const change of input.repoChanges ?? []) {
    const repoRelativePath = safeRepoRelativePath(change.repoRelativePath);
    if (!repoRelativePath) {
      ignoredChanges.push({ reason: "unsafe_repo_relative_path" });
      continue;
    }
    if (!approvedRepoIds.has(change.repoIdentityHash)) {
      ignoredChanges.push({ repoRelativePath, reason: "no_approved_repo" });
      continue;
    }

    const changedAt = parseTimestamp(change.changedAt);
    const matchingWindows = windows.filter((window) => windowContains(window, changedAt));
    if (matchingWindows.length === 0) {
      ignoredChanges.push({ repoRelativePath, repoIdentityHash: change.repoIdentityHash, reason: "stale_activity_window" });
      continue;
    }

    const competing = new Set(matchingWindows.map((window) => window.toolId)).size > 1;
    for (const window of matchingWindows) {
      correlations.push({
        toolId: window.toolId,
        provider: window.provider,
        repoIdentityHash: change.repoIdentityHash,
        repoRelativePath,
        changedAt: changedAt.toISOString(),
        activityWindowStartedAt: window.startedAt.toISOString(),
        activityWindowEndedAt: window.endedAt.toISOString(),
        confidence: competing ? COMPETING_TOOL_CONFIDENCE : SINGLE_TOOL_CONFIDENCE,
        reasonCodes: competing
          ? ["gui_activity_window", "repo_changed", "competing_ai_tools"]
          : ["gui_activity_window", "repo_changed", "single_ai_tool"]
      });
    }
  }

  return { correlations, ignoredChanges };
}

export function activityWindow(signal, options = {}) {
  if (!["frontmost", "recently_active"].includes(signal?.status)) return null;
  const observedAt = parseTimestamp(signal.detectedAt ?? signal.observedAt);
  const lookbackMs = options.lookbackMs ?? DEFAULT_LOOKBACK_MS;
  const durationMs = options.durationMs ?? DEFAULT_DURATION_MS;
  return {
    toolId: requiredSafeString(signal.toolId ?? signal.provider),
    provider: requiredSafeString(signal.provider),
    startedAt: new Date(observedAt.getTime() - lookbackMs),
    endedAt: new Date(observedAt.getTime() + durationMs)
  };
}

function windowContains(window, changedAt) {
  return changedAt >= window.startedAt && changedAt <= window.endedAt;
}

function parseTimestamp(value) {
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    throw new Error("timestamp must be valid ISO time");
  }
  return timestamp;
}

function requiredSafeString(value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error("tool signals require provider and tool id");
  }
  return value.trim().slice(0, 120);
}

function safeRepoRelativePath(value) {
  if (typeof value !== "string") return null;
  const normalized = value.replaceAll("\\", "/").trim();
  if (normalized === "" || normalized.startsWith("/") || normalized.includes("\0")) return null;
  if (normalized.split("/").includes("..")) return null;
  return normalized.slice(0, 1_000);
}
