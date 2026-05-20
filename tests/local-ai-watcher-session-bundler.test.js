import assert from "node:assert/strict";
import test from "node:test";
import {
  bundleWatcherSession,
  splitDiffByRepoRelativePath
} from "../scripts/local-ai-watcher-session-bundler.mjs";

test("watcher session bundler groups a single settled file into one local session payload", () => {
  const payload = bundleWatcherSession(singleFileReconciliation(), baseOptions());

  assert.equal(payload.sourceKind, "local_ai_session");
  assert.equal(payload.organizationId, "org-demo");
  assert.equal(payload.repoIdentityHash, "repo-watch-1");
  assert.equal(payload.repositoryDisplayLabel, "fixture/watch-repo");
  assert.equal(payload.toolProvider, "watcher");
  assert.equal(payload.autoAttribution, "manual_or_unknown");
  assert.equal(payload.attributionConfidence, 0.2);
  assert.deepEqual(payload.attributionReasons, ["repo_changed"]);
  assert.equal(payload.timestampBucket, "2026-05-20T01:00Z");
  assert.equal(payload.artifacts.length, 3);
  assert.deepEqual(
    payload.artifacts.map((artifact) => `${artifact.itemType}:${artifact.repoRelativePath}`),
    ["file_before:src/service.ts", "file_after:src/service.ts", "diff:src/service.ts"]
  );
  assert.equal(payload.artifacts.every((artifact) => artifact.contentHash.length === 64), true);
  assert.match(payload.artifacts.find((artifact) => artifact.itemType === "file_after").content, /newValue/);
});

test("watcher session bundler groups multiple settled files into one session window", () => {
  const reconciliation = {
    ...singleFileReconciliation(),
    diffCandidates: [{ repoRelativePath: "src/a.ts" }, { repoRelativePath: "src/b.ts" }],
    beforeSnapshots: [
      captured("src/a.ts", "export const a = 1;\n"),
      captured("src/b.ts", "export const b = 1;\n")
    ],
    afterSnapshots: [
      captured("src/a.ts", "export const a = 2;\n"),
      captured("src/b.ts", "export const b = 2;\n")
    ],
    diff:
      "diff --git a/src/a.ts b/src/a.ts\n" +
      "--- a/src/a.ts\n" +
      "+++ b/src/a.ts\n" +
      "@@\n" +
      "-export const a = 1;\n" +
      "+export const a = 2;\n" +
      "diff --git a/src/b.ts b/src/b.ts\n" +
      "--- a/src/b.ts\n" +
      "+++ b/src/b.ts\n" +
      "@@\n" +
      "-export const b = 1;\n" +
      "+export const b = 2;\n"
  };

  const payload = bundleWatcherSession(reconciliation, baseOptions());

  assert.equal(payload.artifacts.length, 6);
  assert.equal(new Set(payload.artifacts.map((artifact) => artifact.repoRelativePath)).size, 2);
  assert.equal(payload.idempotencyKey.startsWith("watcher:repo-watch-1:"), true);
});

test("watcher session bundler keeps no-tool sessions manual or unknown", () => {
  const payload = bundleWatcherSession(singleFileReconciliation(), baseOptions());

  assert.equal(payload.autoAttribution, "manual_or_unknown");
  assert.deepEqual(payload.attributionReasons, ["repo_changed"]);
  assert.equal(payload.artifacts.some((artifact) => artifact.itemType === "tool_event"), false);
});

test("watcher session bundler attaches competing tool signals with safe reason codes", () => {
  const payload = bundleWatcherSession(singleFileReconciliation(), {
    ...baseOptions(),
    toolActivities: [
      {
        toolId: "codex-cli",
        provider: "codex",
        invocationId: "codex-1",
        commandClassification: "non_interactive_cli",
        capabilityFlags: ["cli_transcript", "patch_output"],
        startedAt: "2026-05-20T01:00:03Z",
        endedAt: "2026-05-20T01:00:05Z"
      },
      {
        toolId: "gemini-cli",
        provider: "gemini",
        invocationId: "gemini-1",
        commandClassification: "non_interactive_cli",
        capabilityFlags: ["cli_transcript"],
        observedAt: "2026-05-20T01:00:06Z"
      }
    ]
  });

  assert.equal(payload.autoAttribution, "ai_assisted");
  assert.equal(payload.attributionConfidence, 0.58);
  assert.deepEqual(payload.attributionReasons, ["repo_changed", "cli_shim", "patch_match", "competing_ai_tools"]);
  const toolEvents = payload.artifacts.filter((artifact) => artifact.itemType === "tool_event");
  assert.equal(toolEvents.length, 2);
  assert.ok(toolEvents.every((artifact) => artifact.content === null));
  assert.ok(toolEvents.every((artifact) => artifact.metadata.reasonCodes.includes("cli_shim")));
  assert.equal(JSON.stringify(payload).includes("/Users/"), false);
});

test("watcher session bundler drops unsafe, oversized, and secret-bearing artifacts", () => {
  const reconciliation = {
    ...singleFileReconciliation(),
    diffCandidates: [
      { repoRelativePath: "src/service.ts" },
      { repoRelativePath: ".env.local" },
      { repoRelativePath: "src/private.key" },
      { repoRelativePath: "assets/logo.png" }
    ],
    beforeSnapshots: [
      captured("src/service.ts", "export const oldValue = 1;\n"),
      captured(".env.local", "TOKEN=sk-secretsecretsecretsecret\n"),
      captured("src/private.key", "-----BEGIN PRIVATE KEY-----\nsecret\n"),
      captured("assets/logo.png", "not really image")
    ],
    afterSnapshots: [
      captured("src/service.ts", "export const newValue = 2;\n"),
      captured("src/secrets.ts", "export const password = \"supersecretvalue123\";\n"),
      captured("src/huge.ts", "x".repeat(210 * 1024)),
      { repoRelativePath: "src/large.ts", status: "metadata_only", reason: "oversized_after_snapshot", contentText: null }
    ],
    diff:
      "diff --git a/src/service.ts b/src/service.ts\n" +
      "--- a/src/service.ts\n" +
      "+++ b/src/service.ts\n" +
      "@@\n" +
      "-export const oldValue = 1;\n" +
      "+export const newValue = 2;\n" +
      "diff --git a/.env.local b/.env.local\n" +
      "+TOKEN=sk-secretsecretsecretsecret\n"
  };

  const payload = bundleWatcherSession(reconciliation, baseOptions());
  const serialized = JSON.stringify(payload);

  assert.deepEqual(
    payload.artifacts.map((artifact) => `${artifact.itemType}:${artifact.repoRelativePath}`),
    ["file_before:src/service.ts", "file_after:src/service.ts", "diff:src/service.ts"]
  );
  assert.equal(serialized.includes("sk-secret"), false);
  assert.equal(serialized.includes("PRIVATE KEY"), false);
  assert.equal(serialized.includes("supersecretvalue123"), false);
  assert.equal(serialized.includes("src/huge.ts"), false);
});

test("watcher session bundler rejects sessions with no safe code artifacts", () => {
  assert.throws(
    () =>
      bundleWatcherSession(
        {
          ...singleFileReconciliation(),
          diffCandidates: [{ repoRelativePath: ".env.local" }],
          beforeSnapshots: [captured(".env.local", "TOKEN=sk-secretsecretsecretsecret\n")],
          afterSnapshots: [],
          diff: "diff --git a/.env.local b/.env.local\n+TOKEN=sk-secretsecretsecretsecret\n"
        },
        {
          ...baseOptions(),
          toolActivities: [
            {
              toolId: "codex-cli",
              provider: "codex",
              invocationId: "codex-1",
              commandClassification: "non_interactive_cli",
              capabilityFlags: ["cli_transcript"]
            }
          ]
        }
      ),
    /at least one safe code artifact/
  );
});

test("diff splitter maps git diff sections by repo-relative path", () => {
  const sections = splitDiffByRepoRelativePath(
    "diff --git a/src/a.ts b/src/a.ts\n+a\n" +
      "diff --git a/src/b.ts b/src/b.ts\n+b\n"
  );

  assert.deepEqual([...sections.keys()], ["src/a.ts", "src/b.ts"]);
  assert.match(sections.get("src/b.ts"), /\+b/);
});

function baseOptions() {
  return {
    organizationId: "org-demo",
    repoIdentityHash: "repo-watch-1",
    repositoryDisplayLabel: "fixture/watch-repo",
    observedAt: "2026-05-20T01:00:30Z"
  };
}

function singleFileReconciliation() {
  return {
    status: "ok",
    branchName: "feature/watch",
    remoteUrl: "https://example.com/org/watch-repo.git",
    diffCandidates: [{ repoRelativePath: "src/service.ts" }],
    beforeSnapshots: [captured("src/service.ts", "export const oldValue = 1;\n")],
    afterSnapshots: [captured("src/service.ts", "export const newValue = 2;\n")],
    metadataOnlyFiles: [],
    ignoredFiles: [],
    diff:
      "diff --git a/src/service.ts b/src/service.ts\n" +
      "--- a/src/service.ts\n" +
      "+++ b/src/service.ts\n" +
      "@@\n" +
      "-export const oldValue = 1;\n" +
      "+export const newValue = 2;\n",
    diffTruncated: false,
    diffMetadata: { contentTruncated: false, limitReason: null }
  };
}

function captured(repoRelativePath, contentText) {
  return {
    repoRelativePath,
    status: "captured",
    reason: null,
    contentText,
    sizeBytes: Buffer.byteLength(contentText, "utf8"),
    contentHash: "a".repeat(64)
  };
}
