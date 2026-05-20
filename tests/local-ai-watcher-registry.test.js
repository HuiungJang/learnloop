import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";
import { LocalAiWatcherRegistry } from "../scripts/local-ai-watcher-registry.mjs";

test("watcher registry starts only approved repositories and stops revoked, missing, and ignored repositories", () => {
  const closed = [];
  const registry = new LocalAiWatcherRegistry({
    watchFactory: (repoRoot, listener) => {
      assert.equal(path.isAbsolute(repoRoot), true);
      listener();
      return { close: () => closed.push(repoRoot) };
    },
    clock: fixedClock()
  });

  const approved = registry.updateRepository(repository({ status: "approved" }));
  assert.equal(approved.state, "active");
  assert.equal(approved.repositoryStatus, "approved");
  assert.equal(approved.eventCount, 1);
  assert.equal(approved.repoRoot, undefined);

  const secondApprove = registry.updateRepository(repository({ status: "approved", repositoryDisplayLabel: "renamed" }));
  assert.equal(secondApprove.state, "active");
  assert.equal(closed.length, 0);
  assert.equal(secondApprove.repositoryDisplayLabel, "renamed");

  const revoked = registry.updateRepository(repository({ status: "revoked" }));
  assert.equal(revoked.state, "stopped");
  assert.equal(revoked.reason, "repository_revoked");
  assert.equal(closed.length, 1);

  const reapproved = registry.updateRepository(repository({ status: "approved" }));
  assert.equal(reapproved.state, "active");

  const missing = registry.updateRepository(repository({ status: "missing" }));
  assert.equal(missing.state, "stopped");
  assert.equal(missing.reason, "repository_missing");
  assert.equal(closed.length, 2);

  registry.updateRepository(repository({ status: "approved" }));
  const ignored = registry.updateRepository(repository({ status: "always_ignored" }));
  assert.equal(ignored.state, "stopped");
  assert.equal(ignored.reason, "repository_always_ignored");
  assert.equal(closed.length, 3);
});

test("watcher registry surfaces unavailable and degraded watcher states", () => {
  const unavailable = new LocalAiWatcherRegistry({ clock: fixedClock() }).updateRepository({
    repoIdentityHash: "repo-no-root",
    repositoryDisplayLabel: "/Users/example/private-repo",
    status: "approved"
  });
  assert.equal(unavailable.state, "unavailable");
  assert.equal(unavailable.reason, "missing_repo_root");
  assert.equal(unavailable.repositoryDisplayLabel, "private-repo");

  const degraded = new LocalAiWatcherRegistry({
    watchFactory: () => {
      throw new Error("watch failed");
    },
    clock: fixedClock()
  }).updateRepository(repository({ repoIdentityHash: "repo-degraded" }));
  assert.equal(degraded.state, "degraded");
  assert.equal(degraded.reason, "watch_start_failed");
});

function repository(overrides = {}) {
  return {
    repoIdentityHash: overrides.repoIdentityHash ?? "repo-123",
    repositoryDisplayLabel: overrides.repositoryDisplayLabel ?? "fixture/repo",
    repoRoot: overrides.repoRoot ?? "/tmp/learnloop-fixture-repo",
    status: overrides.status ?? "approved"
  };
}

function fixedClock() {
  return () => new Date("2026-05-20T00:00:00.000Z");
}
