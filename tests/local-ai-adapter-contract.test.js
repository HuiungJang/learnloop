import assert from "node:assert/strict";
import test from "node:test";
import {
  ADAPTER_CAPABILITY_FLAGS,
  CAPABILITY_ATTRIBUTION_RULES,
  attributionConfidenceForCapabilities,
  normalizeToolAdapterEvent
} from "../scripts/local-ai-adapter-contract.mjs";

for (const capabilityFlag of ADAPTER_CAPABILITY_FLAGS) {
  test(`tool adapter event contract accepts ${capabilityFlag}`, () => {
    const event = normalizeToolAdapterEvent(fakeAdapterEvent({ capabilityFlags: [capabilityFlag] }));

    assert.equal(event.schemaVersion, 1);
    assert.equal(event.toolId, "codex-cli");
    assert.equal(event.provider, "codex");
    assert.equal(event.invocationId, "invocation-123");
    assert.equal(event.cwd, "/Users/example/project");
    assert.equal(event.repoRoot, "/Users/example/project");
    assert.equal(event.timestamps.startedAt, "2026-05-20T01:00:00.000Z");
    assert.equal(event.timestamps.endedAt, "2026-05-20T01:00:03.000Z");
    assert.equal(event.timestamps.observedAt, "2026-05-20T01:00:04.000Z");
    assert.equal(event.command.classification, "non_interactive_cli");
    assert.equal(event.command.executable, "codex");
    assert.equal(event.command.argvCount, 2);
    assert.equal(event.outputExcerpt.stdoutBytes, 120);
    assert.equal(event.outputExcerpt.stderrBytes, 10);
    assert.equal(event.outputExcerpt.excerptBytes, 64);
    assert.equal(event.outputExcerpt.excerptLimitBytes, 256);
    assert.equal(event.outputExcerpt.excerptHash, "a".repeat(64));
    assert.equal(event.outputExcerpt.truncated, true);
    assert.equal(event.outputExcerpt.suppressed, true);
    assert.deepEqual(event.capabilityFlags, [capabilityFlag]);
    assert.equal(event.attribution.confidence, CAPABILITY_ATTRIBUTION_RULES[capabilityFlag].confidence);
    assert.deepEqual(event.attribution.reasonCodes, [CAPABILITY_ATTRIBUTION_RULES[capabilityFlag].reasonCode]);
  });
}

test("tool adapter event contract deduplicates capabilities and combines confidence", () => {
  const attribution = attributionConfidenceForCapabilities(["process_signal", "patch_output", "patch_output"]);

  assert.equal(attribution.confidence, 0.88);
  assert.deepEqual(attribution.reasonCodes, ["process_signal", "patch_output"]);
});

test("tool adapter event contract rejects unknown capabilities and parser-owned payload fields", () => {
  assert.throws(
    () => normalizeToolAdapterEvent(fakeAdapterEvent({ capabilityFlags: ["provider_specific_ast"] })),
    /Unsupported adapter capability/
  );

  const event = normalizeToolAdapterEvent(
    fakeAdapterEvent({
      outputExcerpt: {
        stdoutBytes: 12,
        excerptText: "raw provider output must stay outside this contract"
      },
      providerPatch: "-secret\n+secret"
    })
  );

  assert.equal("excerptText" in event.outputExcerpt, false);
  assert.equal("providerPatch" in event, false);
});

test("tool adapter event contract normalizes blank optional fields to null", () => {
  const event = normalizeToolAdapterEvent(
    fakeAdapterEvent({
      repoRoot: "   ",
      executable: "   ",
      outputExcerpt: {
        excerptHash: "   "
      }
    })
  );

  assert.equal(event.repoRoot, null);
  assert.equal(event.command.executable, null);
  assert.equal(event.outputExcerpt.excerptHash, null);
});

function fakeAdapterEvent(overrides = {}) {
  return {
    toolId: "codex-cli",
    provider: "codex",
    invocationId: "invocation-123",
    cwd: "/Users/example/project",
    repoRoot: "/Users/example/project",
    startedAt: "2026-05-20T01:00:00Z",
    endedAt: "2026-05-20T01:00:03Z",
    observedAt: "2026-05-20T01:00:04Z",
    commandClassification: "non_interactive_cli",
    executable: "codex",
    argvCount: 2,
    capabilityFlags: ["process_signal"],
    outputExcerpt: {
      stdoutBytes: 120,
      stderrBytes: 10,
      excerptBytes: 64,
      excerptLimitBytes: 256,
      excerptHash: "a".repeat(64),
      truncated: true,
      suppressed: true
    },
    ...overrides
  };
}
