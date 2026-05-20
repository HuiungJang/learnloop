import assert from "node:assert/strict";
import test from "node:test";
import { parseClaudeOutputEvent } from "../scripts/local-ai-claude-output-parser.mjs";

test("Claude output parser extracts exact git diff patches", () => {
  const result = parseClaudeOutputEvent(shimEndEvent({
    stdoutExcerpt: `diff --git a/src/app.ts b/src/app.ts
index 1111111..2222222 100644
--- a/src/app.ts
+++ b/src/app.ts
@@ -1 +1 @@
-export const mode = "manual";
+export const mode = "ai";
`
  }));

  assert.equal(result.parseStatus, "patch");
  assert.equal(result.metadata.patchFormat, "git_diff");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "patch_output"]);
  assert.equal(result.adapterEvent.attribution.confidence, 0.88);
  assert.equal(result.artifacts.length, 1);
  assert.equal(result.artifacts[0].repoRelativePath, "src/app.ts");
  assert.match(result.artifacts[0].content, /diff --git/);
});

test("Claude output parser extracts edited apply_patch blocks", () => {
  const result = parseClaudeOutputEvent(shimEndEvent({
    stdoutExcerpt: `Here is the edit:
*** Begin Patch
*** Update File: src/service.ts
@@
-return oldValue;
+return newValue;
*** End Patch
Done.`
  }));

  assert.equal(result.parseStatus, "patch");
  assert.equal(result.metadata.patchFormat, "apply_patch");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "patch_output"]);
  assert.equal(result.artifacts.length, 1);
  assert.equal(result.artifacts[0].repoRelativePath, "src/service.ts");
  assert.match(result.artifacts[0].content, /\*\*\* Begin Patch/);
});

test("Claude output parser accepts stable stream-json only when requested", () => {
  const stdoutExcerpt = [
    JSON.stringify({ type: "system", subtype: "init" }),
    JSON.stringify({ type: "assistant", message: { content: [{ type: "text", text: "done" }] } }),
    JSON.stringify({ type: "result", subtype: "success", is_error: false })
  ].join("\n");

  const ignored = parseClaudeOutputEvent(shimEndEvent({ stdoutExcerpt }));
  assert.equal(ignored.parseStatus, "metadata_only");
  assert.deepEqual(ignored.adapterEvent.capabilityFlags, ["cli_transcript"]);

  const result = parseClaudeOutputEvent(shimEndEvent({ stdoutExcerpt, outputFormat: "stream-json" }));
  assert.equal(result.parseStatus, "json_stream");
  assert.equal(result.metadata.outputFormat, "stream-json");
  assert.equal(result.metadata.jsonEventCount, 3);
  assert.equal(result.metadata.jsonEventTypes, "system,assistant,result");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript", "json_stream"]);
  assert.deepEqual(result.artifacts, []);
});

test("Claude output parser isolates malformed stream-json failures", () => {
  const result = parseClaudeOutputEvent(shimEndEvent({
    stdoutExcerpt: '{"type":"assistant"}\nnot-json',
    outputFormat: "stream-json"
  }));

  assert.equal(result.parseStatus, "metadata_only");
  assert.equal(result.parseErrorCode, "malformed_json_stream");
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript"]);
  assert.deepEqual(result.artifacts, []);
});

test("Claude output parser falls back to low-confidence metadata for no-match output", () => {
  const result = parseClaudeOutputEvent(shimEndEvent({
    stdoutExcerpt: "I updated the code and ran the tests."
  }));

  assert.equal(result.parseStatus, "metadata_only");
  assert.equal(result.parseErrorCode, null);
  assert.deepEqual(result.adapterEvent.capabilityFlags, ["cli_transcript"]);
  assert.equal(result.adapterEvent.attribution.confidence, 0.65);
  assert.deepEqual(result.artifacts, []);
  assert.equal(result.adapterEvent.outputExcerpt.suppressed, false);
});

function shimEndEvent(overrides = {}) {
  const stdoutExcerpt = overrides.stdoutExcerpt ?? "";
  return {
    type: "shim_end",
    provider: "claude_code",
    invocationId: "claude-test-invocation",
    cwd: "/repo",
    command: "claude",
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
