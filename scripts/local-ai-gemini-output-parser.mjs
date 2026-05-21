import { createHash } from "node:crypto";
import path from "node:path";
import { normalizeToolAdapterEvent } from "./local-ai-adapter-contract.mjs";

const PARSER_NAME = "gemini_output_v1";
const GEMINI_TOOL_ID = "gemini-cli";
const GEMINI_PROVIDER = "gemini";

export function parseGeminiOutputEvent(event, options = {}) {
  try {
    return parseGeminiOutputEventUnsafe(event, options);
  } catch {
    return fallbackResult(options, "parse_failed");
  }
}

function parseGeminiOutputEventUnsafe(event, options) {
  const input = objectOrEmpty(event);
  const stdoutExcerpt = safeText(input.stdoutExcerpt);
  let parseStatus = "metadata_only";
  let parseErrorCode = null;
  let metadata = {};
  let artifacts = [];
  let capabilityFlags = ["cli_transcript"];

  const patchResult = parsePatchLikeOutput(stdoutExcerpt);
  if (patchResult.artifacts.length > 0) {
    parseStatus = "patch";
    metadata = patchResult.metadata;
    artifacts = patchResult.artifacts;
    capabilityFlags = ["cli_transcript", "patch_output"];
  } else if (stableJsonRequested(input, options)) {
    const jsonResult = parseStableJson(stdoutExcerpt);
    if (jsonResult.ok) {
      parseStatus = "json_stream";
      metadata = jsonResult.metadata;
      capabilityFlags = ["cli_transcript", "json_stream"];
    } else if (jsonResult.errorCode) {
      parseErrorCode = jsonResult.errorCode;
    }
  }

  return parserResult({ input, stdoutExcerpt, capabilityFlags, parseStatus, parseErrorCode, metadata, artifacts, options });
}

function fallbackResult(options, parseErrorCode) {
  return parserResult({
    input: {},
    stdoutExcerpt: "",
    capabilityFlags: ["cli_transcript"],
    parseStatus: "metadata_only",
    parseErrorCode,
    metadata: {},
    artifacts: [],
    options
  });
}

function parserResult({ input, stdoutExcerpt, capabilityFlags, parseStatus, parseErrorCode, metadata, artifacts, options }) {
  return {
    parser: PARSER_NAME,
    parseStatus,
    parseErrorCode,
    metadata,
    artifacts,
    adapterEvent: normalizeToolAdapterEvent(adapterEventInput(input, stdoutExcerpt, capabilityFlags, options))
  };
}

function parsePatchLikeOutput(value) {
  const gitDiffArtifacts = parseGitDiffArtifacts(value);
  if (gitDiffArtifacts.length > 0) {
    return {
      artifacts: gitDiffArtifacts,
      metadata: { patchFormat: "git_diff", patchCount: gitDiffArtifacts.length }
    };
  }

  const applyPatchArtifacts = parseApplyPatchArtifacts(value);
  return {
    artifacts: applyPatchArtifacts,
    metadata: applyPatchArtifacts.length > 0 ? { patchFormat: "apply_patch", patchCount: applyPatchArtifacts.length } : {}
  };
}

function parseGitDiffArtifacts(value) {
  const lines = value.split(/\r?\n/);
  const artifacts = [];
  let currentPath = null;
  let currentLines = [];

  function flush() {
    if (!currentPath || currentLines.length === 0) return;
    artifacts.push(diffArtifact(currentPath, currentLines.join("\n"), "git_diff"));
  }

  for (const line of lines) {
    const match = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
    if (match) {
      flush();
      currentPath = normalizeRepoRelativePath(match[2]) ?? normalizeRepoRelativePath(match[1]);
      currentLines = currentPath ? [line] : [];
      continue;
    }
    if (currentPath) currentLines.push(line);
  }
  flush();
  return artifacts;
}

function parseApplyPatchArtifacts(value) {
  const artifacts = [];
  const blocks = value.match(/\*\*\* Begin Patch[\s\S]*?\*\*\* End Patch/g) ?? [];
  for (const block of blocks) {
    const fileMatches = block.matchAll(/^\*\*\* (?:Add|Update|Delete) File: (.+)$/gm);
    for (const fileMatch of fileMatches) {
      const repoRelativePath = normalizeRepoRelativePath(fileMatch[1]);
      if (repoRelativePath) artifacts.push(diffArtifact(repoRelativePath, block, "apply_patch"));
    }
  }
  return artifacts;
}

function parseStableJson(value) {
  const trimmed = value.trim();
  if (trimmed === "") return { ok: false };

  let parsed;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return { ok: false, errorCode: "malformed_json" };
  }
  if (typeof parsed !== "object" || parsed === null || !Array.isArray(parsed.candidates)) {
    return { ok: false, errorCode: "unsupported_json" };
  }

  return {
    ok: true,
    metadata: {
      outputFormat: "json",
      candidateCount: parsed.candidates.length
    }
  };
}

function stableJsonRequested(input, options) {
  return input.outputFormat === "json" || options.outputFormat === "json";
}

function adapterEventInput(input, stdoutExcerpt, capabilityFlags, options) {
  const endedAt = safeTimestamp(input.endedAt) ?? safeTimestamp(options.endedAt) ?? new Date(0).toISOString();
  const startedAt = safeTimestamp(input.startedAt) ?? safeTimestamp(options.startedAt) ?? endedAt;
  return {
    toolId: safeString(input.toolId) || GEMINI_TOOL_ID,
    provider: GEMINI_PROVIDER,
    invocationId: safeString(input.invocationId) || `gemini-${sha256Hex(stdoutExcerpt || endedAt).slice(0, 16)}`,
    cwd: safeString(input.cwd) || safeString(options.cwd) || "/unknown",
    repoRoot: safeString(input.repoRoot ?? options.repoRoot),
    startedAt,
    endedAt,
    observedAt: safeTimestamp(input.observedAt) ?? endedAt,
    commandClassification: safeString(input.commandClassification) || "non_interactive_cli",
    executable: safeString(input.command) || "gemini",
    argvCount: nonNegativeInteger(input.argvCount),
    outputExcerpt: {
      stdoutBytes: nonNegativeInteger(input.stdoutBytes) ?? Buffer.byteLength(stdoutExcerpt, "utf8"),
      stderrBytes: nonNegativeInteger(input.stderrBytes) ?? 0,
      excerptBytes: Buffer.byteLength(stdoutExcerpt, "utf8"),
      excerptLimitBytes: nonNegativeInteger(input.excerptLimitBytes) ?? 0,
      excerptHash: stdoutExcerpt ? sha256Hex(stdoutExcerpt) : null,
      truncated: input.stdoutSuppressed === true,
      suppressed: stdoutExcerpt === "" || input.stdoutSuppressed === true
    },
    capabilityFlags
  };
}

function diffArtifact(repoRelativePath, content, patchFormat) {
  return {
    itemType: "diff",
    repoRelativePath,
    content,
    metadata: {
      parser: PARSER_NAME,
      patchFormat
    }
  };
}

function normalizeRepoRelativePath(value) {
  if (typeof value !== "string" || value.trim() === "" || value.includes("\0")) return null;
  const normalized = path.posix.normalize(value.trim().replace(/\\/g, "/"));
  if (normalized === "." || normalized === ".." || normalized.startsWith("../") || path.posix.isAbsolute(normalized)) {
    return null;
  }
  return normalized;
}

function objectOrEmpty(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value : {};
}

function safeString(value) {
  return typeof value === "string" ? value.trim() : "";
}

function safeText(value) {
  return typeof value === "string" ? value : "";
}

function safeTimestamp(value) {
  if (typeof value !== "string" || value.trim() === "") return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function nonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
