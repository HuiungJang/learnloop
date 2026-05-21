import assert from "node:assert/strict";
import test from "node:test";
import { createAdapterStatusRegistry } from "../scripts/local-ai-adapter-status.mjs";

test("adapter status keeps provider failures isolated", () => {
  const registry = createAdapterStatusRegistry();
  registry.record({
    type: "adapter_error",
    provider: "claude_code",
    runtimeProblem: "parser_failed",
    errorCode: "PARSER",
    stdoutExcerpt: "raw prompt and code must not be retained"
  });
  registry.record({
    type: "shim_health",
    provider: "gemini_cli",
    runtimeStatus: "broken",
    runtimeProblem: "spawn_error",
    errorCode: "ENOENT"
  });
  registry.record({
    type: "shim_start",
    provider: "codex_cli",
    startedAt: "2026-05-20T00:00:00.000Z"
  });
  registry.record({
    type: "shim_end",
    provider: "codex_cli",
    exitCode: 0,
    endedAt: "2026-05-20T00:00:01.000Z"
  });

  const snapshot = registry.snapshot();
  assert.equal(snapshot.find((adapter) => adapter.provider === "codex_cli").status, "ok");
  assert.equal(snapshot.find((adapter) => adapter.provider === "claude_code").status, "failed");
  assert.equal(snapshot.find((adapter) => adapter.provider === "gemini_cli").status, "failed");
  assert.equal(snapshot.find((adapter) => adapter.provider === "gemini_cli").reason, "spawn_error");
  assert.equal(JSON.stringify(snapshot).includes("raw prompt"), false);
});

test("adapter status ignores malformed events without blocking later providers", () => {
  const registry = createAdapterStatusRegistry();
  registry.record(null);
  registry.record({ type: "shim_end", provider: "codex_cli", exitCode: 0 });

  assert.equal(registry.snapshot().find((adapter) => adapter.provider === "codex_cli").status, "ok");
});
