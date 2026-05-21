import { createHash } from "node:crypto";
import {
  attributionConfidenceForCapabilities,
  normalizeToolAdapterEvent
} from "./local-ai-adapter-contract.mjs";

const FIXTURE_REPO_ROOT = "/learnloop-fixtures/adapter-repo";
const FIXTURE_REPO_LABEL = "fixture/adapter-repo";
const FIXTURE_REPO_IDENTITY_HASH = sha256Hex(FIXTURE_REPO_LABEL);
const FIXTURE_STARTED_AT = "2026-05-20T01:00:00Z";

export function defaultAdapterFixtures() {
  return [
    adapterFixture("process", {
      capabilityFlags: ["process_signal"],
      commandClassification: "background_process",
      executable: "codex"
    }),
    adapterFixture("cli", {
      capabilityFlags: ["cli_transcript"],
      commandClassification: "non_interactive_cli",
      executable: "codex",
      outputExcerpt: {
        stdoutBytes: 512,
        stderrBytes: 0,
        excerptBytes: 0,
        excerptLimitBytes: 0,
        truncated: false,
        suppressed: true
      }
    }),
    adapterFixture("patch", {
      capabilityFlags: ["patch_output"],
      commandClassification: "non_interactive_cli",
      executable: "codex",
      artifacts: [
        {
          itemType: "diff",
          repoRelativePath: "src/adapter-fixture.ts",
          content: "-export const mode = \"manual\";\n+export const mode = \"ai_assisted\";\n",
          metadata: { fixture: "patch_output" }
        }
      ]
    }),
    adapterFixture("stream", {
      capabilityFlags: ["json_stream"],
      commandClassification: "non_interactive_cli",
      executable: "codex",
      outputExcerpt: {
        stdoutBytes: 256,
        stderrBytes: 0,
        excerptBytes: 0,
        excerptLimitBytes: 0,
        truncated: false,
        suppressed: true
      }
    })
  ];
}

export function replayAdapterFixturesToLocalSessionBundle(fixtures = defaultAdapterFixtures(), options = {}) {
  if (!Array.isArray(fixtures) || fixtures.length === 0) {
    throw new Error("fixtures must contain at least one adapter event");
  }
  const normalizedEvents = fixtures.map((fixture) => normalizeToolAdapterEvent(fixture.event));
  const firstEvent = normalizedEvents[0];
  const lastEvent = normalizedEvents.at(-1);
  const repoIdentityHash = options.repoIdentityHash ?? FIXTURE_REPO_IDENTITY_HASH;
  const replayArtifacts = fixtures.flatMap((fixture) => fixture.artifacts ?? []);
  const artifacts = [
    ...normalizedEvents.map(toolEventArtifact),
    ...replayArtifacts.map(localSessionArtifact)
  ];
  const attribution = combinedAttribution(normalizedEvents);

  return {
    organizationId: options.organizationId ?? "org-demo",
    teamId: options.teamId ?? "team-platform",
    projectId: options.projectId ?? "project-learning",
    title: options.title ?? "Adapter fixture replay",
    sourceKind: "local_ai_session",
    repoIdentityHash,
    repositoryDisplayLabel: options.repositoryDisplayLabel ?? FIXTURE_REPO_LABEL,
    repositoryUrl: null,
    commitSha: null,
    branchName: "fixture/adapter-events",
    toolProvider: firstEvent.toolId,
    toolSessionId: firstEvent.invocationId,
    toolEventId: lastEvent.invocationId,
    timestampBucket: "2026-05-20T01:00Z",
    idempotencyKey: `fixture:${repoIdentityHash}:${normalizedEvents.map((event) => event.invocationId).join("+")}`,
    autoAttribution: "ai_assisted",
    attributionConfidence: attribution.confidence,
    attributionReasons: attribution.reasonCodes,
    artifacts
  };
}

function adapterFixture(name, overrides) {
  const startedAt = new Date(FIXTURE_STARTED_AT);
  const offsetSeconds = ["process", "cli", "patch", "stream"].indexOf(name) + 1;
  const endedAt = new Date(startedAt.getTime() + offsetSeconds * 1_000).toISOString();
  const invocationId = `fixture-${name}-event`;

  const { artifacts, ...eventOverrides } = overrides;
  return {
    name,
    event: {
      toolId: "codex-cli",
      provider: "codex",
      invocationId,
      cwd: FIXTURE_REPO_ROOT,
      repoRoot: FIXTURE_REPO_ROOT,
      startedAt: startedAt.toISOString(),
      endedAt,
      observedAt: endedAt,
      argvCount: 2,
      outputExcerpt: {
        stdoutBytes: 0,
        stderrBytes: 0,
        excerptBytes: 0,
        excerptLimitBytes: 0,
        truncated: false,
        suppressed: true
      },
      ...eventOverrides
    },
    artifacts
  };
}

function toolEventArtifact(event) {
  return localSessionArtifact({
    itemType: "tool_event",
    content: null,
    metadata: {
      event: "adapter_event",
      schemaVersion: String(event.schemaVersion),
      toolId: event.toolId,
      provider: event.provider,
      invocationId: event.invocationId,
      commandClassification: event.command.classification,
      capabilityFlags: event.capabilityFlags.join(","),
      attributionConfidence: String(event.attribution.confidence),
      stdoutBytes: String(event.outputExcerpt.stdoutBytes),
      stderrBytes: String(event.outputExcerpt.stderrBytes)
    },
    limitReason: event.outputExcerpt.suppressed ? "output_suppressed" : null
  });
}

function localSessionArtifact(input) {
  const metadata = stringifyMetadata(input.metadata ?? {});
  const fingerprint = JSON.stringify({
    itemType: input.itemType,
    repoRelativePath: input.repoRelativePath ?? null,
    content: input.content ?? null,
    metadata
  });
  return {
    itemType: input.itemType,
    repoRelativePath: input.repoRelativePath ?? null,
    sizeBytes: byteLength(input.content),
    metadata,
    contentHash: sha256Hex(fingerprint),
    contentTruncated: false,
    limitReason: input.limitReason ?? null,
    content: input.content ?? null
  };
}

function combinedAttribution(events) {
  const capabilityFlags = [];
  for (const event of events) {
    for (const capabilityFlag of event.capabilityFlags) {
      if (!capabilityFlags.includes(capabilityFlag)) capabilityFlags.push(capabilityFlag);
    }
  }
  const attribution = attributionConfidenceForCapabilities(capabilityFlags);
  return {
    confidence: attribution.confidence,
    reasonCodes: [...attribution.reasonCodes, "fixture_replay"]
  };
}

function stringifyMetadata(metadata) {
  return Object.fromEntries(Object.entries(metadata).map(([key, value]) => [key, String(value)]));
}

function byteLength(value) {
  return value === null || value === undefined ? 0 : Buffer.byteLength(value, "utf8");
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
