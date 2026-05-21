export const TOOL_ADAPTER_EVENT_SCHEMA_VERSION = 1;

export const ADAPTER_CAPABILITY_FLAGS = Object.freeze([
  "process_signal",
  "window_signal",
  "cli_transcript",
  "patch_output",
  "json_stream"
]);

export const ADAPTER_COMMAND_CLASSIFICATIONS = Object.freeze([
  "interactive_cli",
  "non_interactive_cli",
  "background_process",
  "gui_activity",
  "unknown"
]);

export const CAPABILITY_ATTRIBUTION_RULES = Object.freeze({
  process_signal: Object.freeze({ confidence: 0.35, reasonCode: "process_signal" }),
  window_signal: Object.freeze({ confidence: 0.3, reasonCode: "window_signal" }),
  cli_transcript: Object.freeze({ confidence: 0.65, reasonCode: "cli_transcript" }),
  patch_output: Object.freeze({ confidence: 0.85, reasonCode: "patch_output" }),
  json_stream: Object.freeze({ confidence: 0.8, reasonCode: "json_stream" })
});

const capabilitySet = new Set(ADAPTER_CAPABILITY_FLAGS);
const commandClassificationSet = new Set(ADAPTER_COMMAND_CLASSIFICATIONS);

export function normalizeToolAdapterEvent(input) {
  const value = requireObject(input, "event");
  const capabilityFlags = normalizeCapabilityFlags(value.capabilityFlags);
  const startedAt = normalizeTimestamp(value.startedAt, "startedAt");
  const endedAt = normalizeOptionalTimestamp(value.endedAt, "endedAt");
  const observedAt = normalizeOptionalTimestamp(value.observedAt, "observedAt") ?? endedAt ?? startedAt;

  return {
    schemaVersion: TOOL_ADAPTER_EVENT_SCHEMA_VERSION,
    toolId: requireString(value.toolId, "toolId", 80),
    provider: requireString(value.provider, "provider", 80),
    invocationId: requireString(value.invocationId, "invocationId", 120),
    cwd: requireString(value.cwd, "cwd", 2_000),
    repoRoot: optionalString(value.repoRoot, "repoRoot", 2_000),
    timestamps: {
      startedAt,
      endedAt,
      observedAt
    },
    command: {
      classification: normalizeCommandClassification(value.commandClassification),
      executable: optionalString(value.executable, "executable", 160),
      argvCount: optionalNonNegativeInteger(value.argvCount, "argvCount")
    },
    outputExcerpt: normalizeOutputExcerpt(value.outputExcerpt),
    capabilityFlags,
    attribution: attributionConfidenceForCapabilities(capabilityFlags)
  };
}

export function attributionConfidenceForCapabilities(capabilityFlags) {
  const flags = normalizeCapabilityFlags(capabilityFlags);
  const rules = flags.map((flag) => CAPABILITY_ATTRIBUTION_RULES[flag]);
  const strongest = Math.max(...rules.map((rule) => rule.confidence));
  const corroborationBonus = Math.min(0.1, Math.max(0, rules.length - 1) * 0.03);
  return {
    confidence: roundConfidence(Math.min(0.95, strongest + corroborationBonus)),
    reasonCodes: rules.map((rule) => rule.reasonCode)
  };
}

export function normalizeCapabilityFlags(value) {
  if (!Array.isArray(value) || value.length === 0) {
    throw new Error("capabilityFlags must contain at least one adapter capability");
  }

  const normalized = [];
  for (const flag of value) {
    if (!capabilitySet.has(flag)) {
      throw new Error(`Unsupported adapter capability: ${flag}`);
    }
    if (!normalized.includes(flag)) {
      normalized.push(flag);
    }
  }
  return normalized;
}

function normalizeCommandClassification(value) {
  if (value === undefined || value === null || value === "") return "unknown";
  if (!commandClassificationSet.has(value)) {
    throw new Error(`Unsupported command classification: ${value}`);
  }
  return value;
}

function normalizeOutputExcerpt(value) {
  const input = value === undefined || value === null ? {} : requireObject(value, "outputExcerpt");
  return {
    stdoutBytes: optionalNonNegativeInteger(input.stdoutBytes, "outputExcerpt.stdoutBytes") ?? 0,
    stderrBytes: optionalNonNegativeInteger(input.stderrBytes, "outputExcerpt.stderrBytes") ?? 0,
    excerptBytes: optionalNonNegativeInteger(input.excerptBytes, "outputExcerpt.excerptBytes") ?? 0,
    excerptLimitBytes: optionalNonNegativeInteger(input.excerptLimitBytes, "outputExcerpt.excerptLimitBytes") ?? 0,
    excerptHash: optionalString(input.excerptHash, "outputExcerpt.excerptHash", 128),
    truncated: input.truncated === true,
    suppressed: input.suppressed !== false
  };
}

function requireObject(value, field) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${field} must be an object`);
  }
  return value;
}

function requireString(value, field, maxLength) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be a non-empty string`);
  }
  return value.trim().slice(0, maxLength);
}

function optionalString(value, field, maxLength) {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string") throw new Error(`${field} must be a string`);
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed.slice(0, maxLength);
}

function optionalNonNegativeInteger(value, field) {
  if (value === undefined || value === null) return null;
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error(`${field} must be a non-negative integer`);
  }
  return value;
}

function normalizeTimestamp(value, field) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`${field} must be an ISO timestamp`);
  }
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    throw new Error(`${field} must be an ISO timestamp`);
  }
  return timestamp.toISOString();
}

function normalizeOptionalTimestamp(value, field) {
  if (value === undefined || value === null || value === "") return null;
  return normalizeTimestamp(value, field);
}

function roundConfidence(value) {
  return Number(value.toFixed(2));
}
