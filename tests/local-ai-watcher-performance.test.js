import assert from "node:assert/strict";
import test from "node:test";
import { runWatcherPerformanceGate } from "../scripts/local-ai-watcher-performance.mjs";

test("watcher performance gate bounds rapid saves across ten approved repos", async () => {
  const summary = await runWatcherPerformanceGate({
    repoCount: 10,
    changedFileCount: 1000,
    saveBurstCount: 3
  });

  assert.equal(summary.watcherCount, 10);
  assert.equal(summary.eventCount, 3000);
  assert.equal(summary.reconciliationCount, 10);
  assert.equal(summary.sessionCount, 10);
  assert.equal(summary.maxPendingChangeCount, 0);
  assert.equal(summary.maxSettledChangeCount, 100);
  assert.equal(summary.totalDroppedEvents, 0);
  assert.equal(summary.estimatedGitCommandCount, 20);
});
