import assert from "node:assert/strict";
import test from "node:test";
import { parseGeminiOutputEvent } from "../scripts/local-ai-gemini-output-parser.mjs";

test("Gemini output parser extracts exact git diff patches", () => {
  const result = parseGeminiOutputEvent(shimEndEvent({
    stdoutExcerpt: `diff --git a/src/gemini.ts b/src/gemini.ts
index 1111111..2222222 100644
--- a/src/gemini.ts
+++ b/src/gemini.ts
@@ -1 +1 @@
-export const provider = "manual";
+export const provider = "gemini";
`
  }));

  assert.equal(result.parseStatus, "patch");
  assert.equal(result.metadata.patchFormat, "git_diff");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "patch_output"]);
  assert.equal(result.artifacts.length, 1);
  assert.equal(result.artifacts[0].repoRelativePath, "src/gemini.ts");
});

test("Gemini output parser extracts edited apply_patch blocks", () => {
  const result = parseGeminiOutputEvent(shimEndEvent({
    stdoutExcerpt: `Patch:
*** Begin Patch
*** Update File: src/gemini-service.ts
@@
-return "old";
+return "new";
*** End Patch`
  }));

  assert.equal(result.parseStatus, "patch");
  assert.equal(result.metadata.patchFormat, "apply_patch");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "patch_output"]);
  assert.equal(result.artifacts[0].repoRelativePath, "src/gemini-service.ts");
});

test("Gemini output parser accepts stable json only when requested", () => {
  const stdoutExcerpt = JSON.stringify({
    candidates: [
      {
        content: {
          parts: [{ text: "done" }]
        }
      }
    ]
  });

  const ignored = parseGeminiOutputEvent(shimEndEvent({ stdoutExcerpt }));
  assert.equal(ignored.parseStatus, "metadata_only");
  assert.deepEqual(ignored.adapterEvent.capabilityFlags, ["cli_transcript"]);

  const result = parseGeminiOutputEvent(shimEndEvent({ stdoutExcerpt, outputFormat: "json" }));
  assert.equal(result.parseStatus, "json_stream");
  assert.equal(result.metadata.outputFormat, "json");
  assert.equal(result.metadata.candidateCount, 1);
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "json_stream"]);
});

test("Gemini output parser isolates malformed stable output", () => {
  const result = parseGeminiOutputEvent(shimEndEvent({
    stdoutExcerpt: '{"candidates":',
    outputFormat: "json"
  }));

  assert.equal(result.parseStatus, "metadata_only");
  assert.equal(result.parseErrorCode, "malformed_json");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript"]);
  assert.deepEqual(result.artifacts, []);
});

test("Gemini output parser falls back for no-match output", () => {
  const result = parseGeminiOutputEvent(shimEndEvent({
    stdoutExcerpt: "Updated the requested files."
  }));

  assert.equal(result.parseStatus, "metadata_only");
  assert.equal(result.parseErrorCode, null);
  assert.equal(result.adapterEvent.attribution.confidence, 0.65);
  assert.deepEqual(result.artifacts, []);
});

test("Gemini output parser isolates parser exceptions", () => {
  const event = {};
  Object.defineProperty(event, "stdoutExcerpt", {
    get() {
      throw new Error("boom");
    }
  });

  const result = parseGeminiOutputEvent(event);
  assert.equal(result.parseStatus, "metadata_only");
  assert.equal(result.parseErrorCode, "parse_failed");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript"]);
});

function shimEndEvent(overrides = {}) {
  const stdoutExcerpt = overrides.stdoutExcerpt ?? "";
  return {
    type: "shim_end",
    provider: "gemini_cli",
    invocationId: "gemini-test-invocation",
    cwd: "/repo",
    command: "gemini",
    startedAt: "2026-05-20T00:00:00.000Z",
    endedAt: "2026-05-20T00:00:01.000Z",
    durationMs: 1000,
    exitCode: 0,
    signal: null,
    stdoutBytes: Buffer.byteLength(stdoutExcerpt, "utf8"),
    stderrBytes: 0,
    stdoutExcerpt,
    stdoutSuppressed: false,
    stderrExcerpt: null,
    stderrSuppressed: false,
    excerptLimitBytes: 4096,
    ...overrides
  };
}
