import assert from "node:assert/strict";
import test from "node:test";
import {
  activityWindow,
  correlateGuiActivityWindows
} from "../scripts/local-ai-gui-correlation.mjs";

test("GUI correlation matches one active tool to approved repo changes", () => {
  const result = correlateGuiActivityWindows({
    approvedRepositories: [{ repoIdentityHash: "repo-1", repoRoot: "/Users/example/project" }],
    toolSignals: [
      {
        toolId: "codex_app",
        provider: "codex_app",
        status: "frontmost",
        detectedAt: "2026-05-20T01:00:00Z"
      }
    ],
    repoChanges: [
      {
        repoIdentityHash: "repo-1",
        repoRelativePath: "src/service.ts",
        changedAt: "2026-05-20T01:00:30Z"
      }
    ]
  });

  assert.deepEqual(result.ignoredChanges, []);
  assert.equal(result.correlations.length, 1);
  assert.deepEqual(result.correlations[0], {
    toolId: "codex_app",
    provider: "codex_app",
    repoIdentityHash: "repo-1",
    repoRelativePath: "src/service.ts",
    changedAt: "2026-05-20T01:00:30.000Z",
    activityWindowStartedAt: "2026-05-20T00:59:30.000Z",
    activityWindowEndedAt: "2026-05-20T01:02:00.000Z",
    confidence: 0.55,
    reasonCodes: ["gui_activity_window", "repo_changed", "single_ai_tool"]
  });
});

test("GUI correlation lowers confidence when multiple AI tools are active", () => {
  const result = correlateGuiActivityWindows({
    approvedRepositories: [{ repoIdentityHash: "repo-1" }],
    toolSignals: [
      { toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" },
      { toolId: "claude_desktop", provider: "claude", status: "recently_active", detectedAt: "2026-05-20T01:00:10Z" }
    ],
    repoChanges: [
      { repoIdentityHash: "repo-1", repoRelativePath: "src/app.ts", changedAt: "2026-05-20T01:00:20Z" }
    ]
  });

  assert.equal(result.correlations.length, 2);
  assert.ok(result.correlations.every((correlation) => correlation.confidence === 0.35));
  assert.ok(result.correlations.every((correlation) => correlation.reasonCodes.includes("competing_ai_tools")));
  assert.deepEqual(result.correlations.map((correlation) => correlation.toolId), ["codex_app", "claude_desktop"]);
});

test("GUI correlation ignores changes for unapproved repos", () => {
  const result = correlateGuiActivityWindows({
    approvedRepositories: [{ repoIdentityHash: "approved-repo" }],
    toolSignals: [
      { toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" }
    ],
    repoChanges: [
      { repoIdentityHash: "other-repo", repoRelativePath: "src/app.ts", changedAt: "2026-05-20T01:00:20Z" }
    ]
  });

  assert.deepEqual(result.correlations, []);
  assert.deepEqual(result.ignoredChanges, [{ repoRelativePath: "src/app.ts", reason: "no_approved_repo" }]);
});

test("GUI correlation ignores stale activity windows", () => {
  const result = correlateGuiActivityWindows({
    approvedRepositories: [{ repoIdentityHash: "repo-1" }],
    toolSignals: [
      { toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" }
    ],
    repoChanges: [
      { repoIdentityHash: "repo-1", repoRelativePath: "src/app.ts", changedAt: "2026-05-20T01:10:00Z" }
    ]
  });

  assert.deepEqual(result.correlations, []);
  assert.deepEqual(result.ignoredChanges, [
    { repoRelativePath: "src/app.ts", repoIdentityHash: "repo-1", reason: "stale_activity_window" }
  ]);
});

test("GUI correlation keeps only repo-relative safe paths", () => {
  const result = correlateGuiActivityWindows({
    approvedRepositories: [{ repoIdentityHash: "repo-1" }],
    toolSignals: [
      { toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" }
    ],
    repoChanges: [
      { repoIdentityHash: "repo-1", repoRelativePath: "/Users/example/project/src/app.ts", changedAt: "2026-05-20T01:00:20Z" },
      { repoIdentityHash: "repo-1", repoRelativePath: "../secret.txt", changedAt: "2026-05-20T01:00:20Z" }
    ]
  });

  assert.deepEqual(result.correlations, []);
  assert.deepEqual(result.ignoredChanges, [
    { reason: "unsafe_repo_relative_path" },
    { reason: "unsafe_repo_relative_path" }
  ]);
});

test("GUI activity windows require actual activity status", () => {
  assert.equal(activityWindow({ toolId: "codex_app", provider: "codex_app", status: "running", detectedAt: "2026-05-20T01:00:00Z" }), null);
});
