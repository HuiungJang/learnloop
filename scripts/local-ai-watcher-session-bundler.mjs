import { createHash } from "node:crypto";
import path from "node:path";

const SAFE_REASON_CODES = new Set([
  "repo_changed",
  "gui_activity_window",
  "cli_shim",
  "patch_match",
  "single_ai_tool",
  "competing_ai_tools"
]);
const BUNDLE_SOURCE_KIND = "local_ai_session";
const DEFAULT_TITLE = "Watcher-collected local AI session";

export function bundleWatcherSession(reconciliation, options = {}) {
  const input = requireObject(reconciliation, "reconciliation");
  const repoIdentityHash = requiredString(options.repoIdentityHash ?? input.repoIdentityHash, "repoIdentityHash");
  const observedAt = normalizeTimestamp(options.observedAt ?? options.now?.() ?? new Date().toISOString(), "observedAt");
  const toolActivities = normalizeToolActivities(options.toolActivities ?? options.toolEvents ?? options.toolSignals ?? []);

  const fileArtifacts = fileSnapshotArtifacts(input.beforeSnapshots, "file_before")
    .concat(fileSnapshotArtifacts(input.afterSnapshots, "file_after"));
  const diffArtifacts = diffSessionArtifacts(input);
  const changeArtifacts = fileArtifacts.concat(diffArtifacts);
  const toolArtifacts = toolActivities.map((activity) => toolEventArtifact(activity, repoIdentityHash, observedAt));
  const artifacts = changeArtifacts.concat(toolArtifacts);

  if (changeArtifacts.length === 0) {
    throw new Error("watcher session requires at least one safe code artifact");
  }

  const attribution = attributionFor(toolActivities, diffArtifacts.length > 0);
  const firstTool = toolActivities[0];
  const seed = idempotencySeed(repoIdentityHash, observedAt, artifacts, toolActivities);
  const sessionHash = sha256Hex(seed);

  return {
    organizationId: requiredString(options.organizationId, "organizationId"),
    teamId: optionalString(options.teamId),
    projectId: optionalString(options.projectId),
    title: optionalString(options.title) ?? DEFAULT_TITLE,
    sourceKind: BUNDLE_SOURCE_KIND,
    repoIdentityHash,
    repositoryDisplayLabel: optionalString(options.repositoryDisplayLabel) ?? repoIdentityHash,
    repositoryUrl: optionalString(options.repositoryUrl ?? input.remoteUrl),
    commitSha: optionalString(options.commitSha),
    branchName: optionalString(options.branchName ?? input.branchName),
    toolProvider: firstTool?.toolId ?? "watcher",
    toolSessionId: firstTool?.invocationId ?? `watcher-${sessionHash.slice(0, 16)}`,
    toolEventId: `watcher-${sessionHash.slice(16, 32)}`,
    timestampBucket: timestampBucket(observedAt),
    idempotencyKey: `watcher:${repoIdentityHash}:${sessionHash}`,
    autoAttribution: attribution.autoAttribution,
    attributionConfidence: attribution.confidence,
    attributionReasons: attribution.reasonCodes,
    artifacts
  };
}

export function splitDiffByRepoRelativePath(diffText) {
  const lines = String(diffText ?? "").split("\n");
  const sections = new Map();
  let currentPath = null;
  let currentLines = [];

  const flush = () => {
    if (!currentPath || currentLines.length === 0) return;
    sections.set(currentPath, currentLines.join("\n"));
  };

  for (const line of lines) {
    const match = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
    if (match) {
      flush();
      const nextPath = normalizeRepoRelativePath(match[2]) ?? normalizeRepoRelativePath(match[1]);
      currentPath = nextPath;
      currentLines = nextPath ? [line] : [];
      continue;
    }
    if (currentPath) currentLines.push(line);
  }
  flush();
  return sections;
}

function fileSnapshotArtifacts(snapshots, itemType) {
  return array(snapshots)
    .filter((snapshot) => snapshot?.status === "captured")
    .map((snapshot) => ({
      repoRelativePath: normalizeRepoRelativePath(snapshot.repoRelativePath),
      content: typeof snapshot.contentText === "string" ? snapshot.contentText : null
    }))
    .filter((snapshot) => snapshot.repoRelativePath && safeCaptureContent(snapshot.content, MAX_TEXT_ARTIFACT_BYTES))
    .map((snapshot) =>
      localSessionArtifact({
        itemType,
        repoRelativePath: snapshot.repoRelativePath,
        content: snapshot.content
      })
    );
}

function diffSessionArtifacts(reconciliation) {
  const diffText = String(reconciliation.diff ?? "");
  if (diffText.trim() === "") return [];

  const candidatePaths = new Set(
    array(reconciliation.diffCandidates)
      .map((file) => normalizeRepoRelativePath(file?.repoRelativePath))
      .filter(Boolean)
  );
  const diffSections = splitDiffByRepoRelativePath(diffText);
  const truncated = reconciliation.diffTruncated === true || reconciliation.diffMetadata?.contentTruncated === true;
  const artifacts = [];

  for (const [repoRelativePath, content] of diffSections.entries()) {
    if (candidatePaths.size > 0 && !candidatePaths.has(repoRelativePath)) continue;
    if (!safeCaptureContent(content, MAX_DIFF_BYTES)) continue;
    artifacts.push(
      localSessionArtifact({
        itemType: "diff",
        repoRelativePath,
        content,
        metadata: truncated ? { truncated: "true" } : {},
        contentTruncated: truncated,
        limitReason: truncated ? "client_truncated" : null
      })
    );
  }
  return artifacts;
}

function toolEventArtifact(activity, repoIdentityHash, observedAt) {
  const metadata = {
    event: "watcher_tool_activity",
    tool: activity.toolId,
    provider: activity.provider,
    repoIdentityHash,
    changedAt: activity.observedAt ?? observedAt,
    confidence: String(activity.confidence),
    reasonCodes: activity.reasonCodes.join(",")
  };
  if (activity.startedAt) metadata.activityWindowStartedAt = activity.startedAt;
  if (activity.endedAt) metadata.activityWindowEndedAt = activity.endedAt;
  return localSessionArtifact({ itemType: "tool_event", content: null, metadata });
}

function attributionFor(toolActivities, hasDiffArtifacts) {
  const reasons = ["repo_changed"];
  if (toolActivities.length === 0) {
    return { autoAttribution: "manual_or_unknown", confidence: 0.2, reasonCodes: reasons };
  }

  const toolKeys = new Set(toolActivities.map((activity) => `${activity.provider}:${activity.toolId}`));
  const hasGuiSignal = toolActivities.some((activity) => activity.reasonCodes.includes("gui_activity_window"));
  const hasCliSignal = toolActivities.some((activity) => activity.reasonCodes.includes("cli_shim"));
  const hasPatchSignal = hasDiffArtifacts && toolActivities.some((activity) => activity.reasonCodes.includes("patch_match"));
  if (hasGuiSignal) reasons.push("gui_activity_window");
  if (hasCliSignal) reasons.push("cli_shim");
  if (hasPatchSignal) reasons.push("patch_match");
  reasons.push(toolKeys.size > 1 ? "competing_ai_tools" : "single_ai_tool");

  let confidence = 0.45;
  if (toolKeys.size > 1) {
    confidence = 0.58;
  } else if (hasPatchSignal) {
    confidence = 0.9;
  } else if (hasCliSignal) {
    confidence = 0.72;
  } else if (hasGuiSignal) {
    confidence = 0.55;
  }
  return {
    autoAttribution: hasGuiSignal && !hasCliSignal && !hasPatchSignal ? "gui_correlated" : "ai_assisted",
    confidence,
    reasonCodes: unique(reasons.filter((reason) => SAFE_REASON_CODES.has(reason)))
  };
}

function normalizeToolActivities(values) {
  return array(values).map(normalizeToolActivity).filter(Boolean);
}

function normalizeToolActivity(value, index) {
  if (typeof value !== "object" || value === null) return null;
  const input = value.event && typeof value.event === "object" ? value.event : value;
  const toolId = safeSlug(input.toolId ?? input.tool ?? input.provider ?? "tool");
  const provider = safeSlug(input.provider ?? input.toolId ?? "provider");
  const invocationId = safeToken(input.invocationId ?? input.toolSessionId ?? input.sessionId) ?? `${toolId}-${index}`;
  const startedAt = normalizeOptionalTimestamp(input.startedAt ?? input.timestamps?.startedAt, "startedAt");
  const endedAt = normalizeOptionalTimestamp(input.endedAt ?? input.timestamps?.endedAt, "endedAt");
  const observedAt = normalizeOptionalTimestamp(input.observedAt ?? input.timestamps?.observedAt ?? endedAt ?? startedAt, "observedAt");
  const capabilityFlags = array(input.capabilityFlags).filter((flag) => typeof flag === "string");
  const commandClassification = input.commandClassification ?? input.command?.classification ?? "";
  const reasonCodes = toolReasonCodes({ capabilityFlags, commandClassification, provider, toolId });
  if (reasonCodes.length === 0) return null;
  return {
    toolId,
    provider,
    invocationId,
    startedAt,
    endedAt,
    observedAt,
    confidence: confidenceForReasonCodes(reasonCodes),
    reasonCodes
  };
}

function toolReasonCodes({ capabilityFlags, commandClassification, provider, toolId }) {
  const reasons = [];
  const flags = new Set(capabilityFlags);
  const identity = `${provider}:${toolId}:${commandClassification}`.toLowerCase();
  if (commandClassification === "gui_activity" || flags.has("window_signal") || identity.includes("app")) {
    reasons.push("gui_activity_window");
  }
  if (
    commandClassification === "interactive_cli" ||
    commandClassification === "non_interactive_cli" ||
    flags.has("cli_transcript") ||
    flags.has("json_stream") ||
    identity.includes("cli")
  ) {
    reasons.push("cli_shim");
  }
  if (flags.has("patch_output")) {
    reasons.push("patch_match");
  }
  return unique(reasons.filter((reason) => SAFE_REASON_CODES.has(reason)));
}

function confidenceForReasonCodes(reasonCodes) {
  if (reasonCodes.includes("patch_match")) return 0.9;
  if (reasonCodes.includes("cli_shim")) return 0.72;
  if (reasonCodes.includes("gui_activity_window")) return 0.55;
  return 0.45;
}

function localSessionArtifact(input) {
  const metadata = stringifyMetadata(input.metadata ?? {});
  const content = input.content ?? null;
  const fingerprint = JSON.stringify({
    itemType: input.itemType,
    repoRelativePath: input.repoRelativePath ?? null,
    content,
    metadata
  });
  return {
    itemType: input.itemType,
    repoRelativePath: input.repoRelativePath ?? null,
    sizeBytes: byteLength(content),
    metadata,
    contentHash: sha256Hex(fingerprint),
    contentTruncated: input.contentTruncated === true,
    limitReason: input.limitReason ?? null,
    content
  };
}

function safeCaptureContent(value, maxBytes) {
  return typeof value === "string" &&
    Buffer.byteLength(value, "utf8") <= maxBytes &&
    !hasSecretLikeContent(value);
}

function hasSecretLikeContent(value) {
  return /-----BEGIN [A-Z ]*PRIVATE KEY-----/.test(value) ||
    /\bsk-[A-Za-z0-9_-]{16,}\b/.test(value) ||
    /\b(?:api[_-]?key|token|secret|password)\b\s*[:=]\s*["']?[A-Za-z0-9_./+=-]{12,}/i.test(value);
}

function normalizeRepoRelativePath(value) {
  if (typeof value !== "string" || value.length === 0 || value.includes("\0")) return null;
  const normalized = path.posix.normalize(value.replace(/\\/g, "/"));
  if (normalized === "." || normalized === ".." || normalized.startsWith("../") || path.posix.isAbsolute(normalized)) {
    return null;
  }
  if (ignoredPathReason(normalized)) return null;
  return normalized;
}

function ignoredPathReason(repoRelativePath) {
  const segments = repoRelativePath.split("/").filter(Boolean);
  const lowerSegments = segments.map((segment) => segment.toLowerCase());
  const name = lowerSegments.at(-1) ?? "";
  if (name.startsWith(".env")) return "sensitive_file";
  if (lowerSegments.some((segment) => segment.startsWith("."))) return "hidden_path";
  if (lowerSegments.some((segment) => IGNORED_DIRECTORIES.has(segment))) return "ignored_directory";
  if (SENSITIVE_FILE_PATTERN.test(name)) return "sensitive_file";
  if (BINARY_OR_ARCHIVE_PATTERN.test(name)) return "binary_or_archive";
  return null;
}

function idempotencySeed(repoIdentityHash, observedAt, artifacts, toolActivities) {
  return JSON.stringify({
    repoIdentityHash,
    observedAt,
    artifacts: artifacts
      .map((artifact) => [artifact.itemType, artifact.repoRelativePath ?? "", artifact.contentHash])
      .sort(),
    tools: toolActivities
      .map((activity) => [activity.provider, activity.toolId, activity.invocationId])
      .sort()
  });
}

function timestampBucket(isoTimestamp) {
  const date = new Date(isoTimestamp);
  if (Number.isNaN(date.getTime())) throw new Error("observedAt must be a valid ISO timestamp");
  return `${date.toISOString().slice(0, 13)}:00Z`;
}

function normalizeTimestamp(value, field) {
  if (value instanceof Date) {
    if (Number.isNaN(value.getTime())) throw new Error(`${field} must be a valid ISO timestamp`);
    return value.toISOString();
  }
  if (typeof value === "number" && Number.isFinite(value)) {
    return new Date(value).toISOString();
  }
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${field} must be a valid ISO timestamp`);
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error(`${field} must be a valid ISO timestamp`);
  return date.toISOString();
}

function normalizeOptionalTimestamp(value, field) {
  if (value === undefined || value === null || value === "") return null;
  return normalizeTimestamp(value, field);
}

function stringifyMetadata(metadata) {
  return Object.fromEntries(
    Object.entries(metadata)
      .filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== "")
      .map(([key, value]) => [key, String(value)])
  );
}

function requiredString(value, field) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${field} is required`);
  return value.trim();
}

function optionalString(value) {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
}

function requireObject(value, field) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error(`${field} must be an object`);
  return value;
}

function safeSlug(value) {
  const raw = typeof value === "string" && value.trim() !== "" ? value.trim() : "unknown";
  return raw.replace(/[^A-Za-z0-9_.+#-]/g, "_").slice(0, 64) || "unknown";
}

function safeToken(value) {
  return typeof value === "string" && value.trim() !== "" ? value.trim().replace(/[^A-Za-z0-9_.:#@+-]/g, "_").slice(0, 120) : null;
}

function array(value) {
  return Array.isArray(value) ? value : [];
}

function unique(values) {
  return values.filter((value, index) => values.indexOf(value) === index);
}

function byteLength(value) {
  return value === null || value === undefined ? 0 : Buffer.byteLength(value, "utf8");
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}

const IGNORED_DIRECTORIES = new Set(["node_modules", "vendor", "dist", "build", "target", ".gradle", ".git"]);
const SENSITIVE_FILE_PATTERN = /\.(?:pem|key|crt|cer|p12|pfx|jks|keystore)$/;
const BINARY_OR_ARCHIVE_PATTERN = /\.(?:png|jpe?g|gif|webp|ico|pdf|zip|tar|gz|tgz|jar|class|so|dylib|dll|exe|bin|mp4|mov|mp3|wav)$/;
const MAX_TEXT_ARTIFACT_BYTES = 200 * 1024;
const MAX_DIFF_BYTES = 1024 * 1024;
