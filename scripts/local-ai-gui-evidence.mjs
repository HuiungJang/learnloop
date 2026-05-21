import { createHash } from "node:crypto";
import { correlateGuiActivityWindows } from "./local-ai-gui-correlation.mjs";

export function guiCorrelationToLocalSessionPayload(input = {}, options = {}) {
  const { correlations } = correlateGuiActivityWindows(input, options);
  if (correlations.length === 0) {
    throw new Error("GUI correlation evidence requires at least one matched repo change");
  }

  const repoIds = [...new Set(correlations.map((correlation) => correlation.repoIdentityHash))];
  if (repoIds.length !== 1) {
    throw new Error("GUI correlation evidence must be grouped by one repository");
  }

  const primary = correlations[0];
  const repoIdentityHash = repoIds[0];
  const attributionReasons = unique(correlations.flatMap((correlation) => correlation.reasonCodes));
  const idempotencySeed = correlations
    .map((correlation) => `${correlation.toolId}:${correlation.repoRelativePath}:${correlation.changedAt}`)
    .sort()
    .join("|");

  return {
    organizationId: requiredString(options.organizationId, "organizationId"),
    teamId: options.teamId ?? null,
    projectId: options.projectId ?? null,
    title: options.title ?? "GUI-correlated local AI session",
    sourceKind: "local_ai_session",
    repoIdentityHash,
    repositoryDisplayLabel: options.repositoryDisplayLabel ?? repoIdentityHash,
    repositoryUrl: requiredString(options.repositoryUrl, "repositoryUrl"),
    commitSha: options.commitSha ?? null,
    branchName: options.branchName ?? null,
    toolProvider: "gui_correlation",
    toolSessionId: `gui-${sha256Hex(idempotencySeed).slice(0, 16)}`,
    toolEventId: `gui-${sha256Hex(`${idempotencySeed}:event`).slice(0, 16)}`,
    timestampBucket: timestampBucket(primary.changedAt),
    idempotencyKey: `gui:${repoIdentityHash}:${sha256Hex(idempotencySeed)}`,
    autoAttribution: "gui_correlated",
    attributionConfidence: Number(Math.max(...correlations.map((correlation) => correlation.confidence)).toFixed(2)),
    attributionReasons,
    artifacts: correlations.map(correlationArtifact)
  };
}

function correlationArtifact(correlation) {
  const metadata = {
    event: "gui_correlation",
    tool: correlation.toolId,
    provider: correlation.provider,
    repoIdentityHash: correlation.repoIdentityHash,
    repoRelativePath: correlation.repoRelativePath,
    changedAt: correlation.changedAt,
    activityWindowStartedAt: correlation.activityWindowStartedAt,
    activityWindowEndedAt: correlation.activityWindowEndedAt,
    confidence: String(correlation.confidence),
    reasonCodes: correlation.reasonCodes.join(",")
  };

  return {
    itemType: "tool_event",
    repoRelativePath: null,
    sizeBytes: 0,
    metadata,
    contentHash: sha256Hex(JSON.stringify(metadata)),
    contentTruncated: false,
    limitReason: "gui_correlation_metadata_only",
    content: null
  };
}

function timestampBucket(isoTimestamp) {
  const date = new Date(isoTimestamp);
  if (Number.isNaN(date.getTime())) throw new Error("changedAt must be a valid ISO timestamp");
  return `${date.toISOString().slice(0, 13)}:00Z`;
}

function requiredString(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} is required`);
  }
  return value.trim();
}

function unique(values) {
  return values.filter((value, index) => values.indexOf(value) === index);
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
