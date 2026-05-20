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

test("watcher registry debounces rapid saves into one settled change set", () => {
  let listener = null;
  const scheduled = [];
  const cleared = [];
  const registry = new LocalAiWatcherRegistry({
    debounceMs: 300,
    watchFactory: (_repoRoot, nextListener) => {
      listener = nextListener;
      return { close: () => {} };
    },
    setTimeoutFn: (callback, delayMs) => {
      const handle = { callback, delayMs, unref: () => {} };
      scheduled.push(handle);
      return handle;
    },
    clearTimeoutFn: (handle) => cleared.push(handle),
    clock: sequenceClock([
      "2026-05-20T00:00:00.000Z",
      "2026-05-20T00:00:00.100Z",
      "2026-05-20T00:00:00.200Z",
      "2026-05-20T00:00:00.300Z",
      "2026-05-20T00:00:01.000Z"
    ])
  });

  const approved = registry.updateRepository(repository());
  assert.equal(approved.debounceMs, 300);

  listener("change", "src/../src/app.ts");
  listener("change", "src/app.ts");
  listener("rename", "src/other.ts");
  assert.equal(cleared.length, 2);
  assert.equal(scheduled.every((handle) => handle.delayMs === 300), true);

  scheduled.at(-1).callback();
  assert.deepEqual(registry.settledChangesFor("repo-123"), [
    { repoRelativePath: "src/app.ts", eventType: "change", changedAt: "2026-05-20T00:00:00.200Z" },
    { repoRelativePath: "src/other.ts", eventType: "rename", changedAt: "2026-05-20T00:00:00.300Z" }
  ]);
  const [status] = registry.list();
  assert.equal(status.pendingChangeCount, 0);
  assert.equal(status.settledChangeCount, 2);
  assert.equal(status.settledBatchCount, 1);
  assert.equal(status.lastSettledAt, "2026-05-20T00:00:01.000Z");
});

test("watcher registry clamps debounce configuration between 250ms and 5s", () => {
  assertDebounceDelay(1, 250);
  assertDebounceDelay(9000, 5000);
});

test("watcher registry bounds pending changes and marks full reconciliation when the queue is full", () => {
  let listener = null;
  const registry = new LocalAiWatcherRegistry({
    maxPendingChanges: 3,
    watchFactory: (_repoRoot, nextListener) => {
      listener = nextListener;
      return { close: () => {} };
    },
    setTimeoutFn: (callback) => ({ callback, unref: () => {} }),
    clearTimeoutFn: () => {},
    clock: fixedClock()
  });

  registry.updateRepository(repository());
  for (let index = 0; index < 20; index += 1) {
    listener("change", `src/file-${index}.ts`);
  }

  const [status] = registry.list();
  assert.equal(status.state, "degraded");
  assert.equal(status.reason, "pending_queue_full");
  assert.equal(status.pendingChangeCount, 3);
  assert.equal(status.droppedEventCount, 17);
  assert.equal(status.needsFullReconciliation, true);
  assert.equal(registry.settledChangesFor("repo-123").length, 0);

  assert.equal(registry.tryStartReconciliation("repo-123"), true);
  registry.finishReconciliation("repo-123", { fullReconciliationCompleted: true });
  const [recovered] = registry.list();
  assert.equal(recovered.state, "active");
  assert.equal(recovered.needsFullReconciliation, false);
  assert.equal(recovered.pendingChangeCount, 0);
});

test("watcher registry limits concurrent reconciliation work across repositories", () => {
  const registry = new LocalAiWatcherRegistry({
    maxConcurrentReconciliations: 1,
    watchFactory: () => ({ close: () => {} }),
    clock: fixedClock()
  });
  registry.updateRepository(repository({ repoIdentityHash: "repo-a" }));
  registry.updateRepository(repository({ repoIdentityHash: "repo-b" }));

  assert.equal(registry.tryStartReconciliation("repo-a"), true);
  assert.equal(registry.tryStartReconciliation("repo-b"), false);
  registry.finishReconciliation("repo-a", { fullReconciliationCompleted: true });
  assert.equal(registry.tryStartReconciliation("repo-b"), true);
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

function sequenceClock(values) {
  let index = 0;
  return () => new Date(values[Math.min(index++, values.length - 1)]);
}

function assertDebounceDelay(configured, expected) {
  let listener = null;
  let delayMs = null;
  const registry = new LocalAiWatcherRegistry({
    debounceMs: configured,
    watchFactory: (_repoRoot, nextListener) => {
      listener = nextListener;
      return { close: () => {} };
    },
    setTimeoutFn: (callback, nextDelayMs) => {
      delayMs = nextDelayMs;
      return { callback, unref: () => {} };
    },
    clearTimeoutFn: () => {},
    clock: fixedClock()
  });
  registry.updateRepository(repository({ repoIdentityHash: `repo-${expected}` }));
  listener("change", "src/app.ts");
  assert.equal(delayMs, expected);
}
