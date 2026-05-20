import assert from "node:assert/strict";
import test from "node:test";
import { guiCorrelationToLocalSessionPayload } from "../scripts/local-ai-gui-evidence.mjs";

test("GUI correlation evidence creates metadata-only local session payload", () => {
  const payload = guiCorrelationToLocalSessionPayload(guiInput(), {
    organizationId: "org-demo",
    teamId: "team-platform",
    projectId: "project-learning",
    repositoryUrl: "file:///learnloop-fixtures/gui-repo",
    repositoryDisplayLabel: "fixture/gui-repo",
    branchName: "feature/gui-correlation"
  });

  assert.equal(payload.sourceKind, "local_ai_session");
  assert.equal(payload.repoIdentityHash, "repo-1");
  assert.equal(payload.toolProvider, "gui_correlation");
  assert.equal(payload.autoAttribution, "gui_correlated");
  assert.equal(payload.attributionConfidence, 0.55);
  assert.deepEqual(payload.attributionReasons, ["gui_activity_window", "repo_changed", "single_ai_tool"]);
  assert.equal(payload.artifacts.length, 1);
  assert.equal(payload.artifacts[0].itemType, "tool_event");
  assert.equal(payload.artifacts[0].content, null);
  assert.equal(payload.artifacts[0].repoRelativePath, null);
  assert.equal(payload.artifacts[0].contentHash.length, 64);
  assert.equal(payload.artifacts[0].metadata.repoRelativePath, "src/service.ts");
  assert.equal(payload.artifacts[0].metadata.activityWindowStartedAt, "2026-05-20T00:59:30.000Z");

  const serialized = JSON.stringify(payload);
  assert.equal(serialized.includes("/Users/"), false);
  assert.equal(serialized.includes("sk-"), false);
  assert.equal(serialized.includes("raw"), false);
});

test("GUI correlation evidence rejects empty and mixed-repository matches", () => {
  assert.throws(
    () =>
      guiCorrelationToLocalSessionPayload(
        { approvedRepositories: [], toolSignals: [], repoChanges: [] },
        { organizationId: "org-demo", repositoryUrl: "file:///learnloop-fixtures/gui-repo" }
      ),
    /requires at least one matched repo change/
  );

  assert.throws(
    () =>
      guiCorrelationToLocalSessionPayload(
        {
          approvedRepositories: [{ repoIdentityHash: "repo-1" }, { repoIdentityHash: "repo-2" }],
          toolSignals: [{ toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" }],
          repoChanges: [
            { repoIdentityHash: "repo-1", repoRelativePath: "src/one.ts", changedAt: "2026-05-20T01:00:20Z" },
            { repoIdentityHash: "repo-2", repoRelativePath: "src/two.ts", changedAt: "2026-05-20T01:00:20Z" }
          ]
        },
        { organizationId: "org-demo", repositoryUrl: "file:///learnloop-fixtures/gui-repo" }
      ),
    /grouped by one repository/
  );
});

function guiInput() {
  return {
    approvedRepositories: [{ repoIdentityHash: "repo-1" }],
    toolSignals: [{ toolId: "codex_app", provider: "codex_app", status: "frontmost", detectedAt: "2026-05-20T01:00:00Z" }],
    repoChanges: [{ repoIdentityHash: "repo-1", repoRelativePath: "src/service.ts", changedAt: "2026-05-20T01:00:30Z" }]
  };
}
