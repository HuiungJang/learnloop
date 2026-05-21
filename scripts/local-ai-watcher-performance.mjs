import { LocalAiWatcherRegistry } from "./local-ai-watcher-registry.mjs";

const DEFAULT_REPO_COUNT = 10;
const DEFAULT_CHANGED_FILE_COUNT = 1000;
const DEFAULT_SAVE_BURST_COUNT = 3;

export async function runWatcherPerformanceGate(options = {}) {
  const repoCount = positiveInteger(options.repoCount, DEFAULT_REPO_COUNT);
  const changedFileCount = positiveInteger(options.changedFileCount, DEFAULT_CHANGED_FILE_COUNT);
  const saveBurstCount = positiveInteger(options.saveBurstCount, DEFAULT_SAVE_BURST_COUNT);
  const listeners = new Map();
  const scheduled = new Map();
  const reconciliations = [];
  let sequence = 0;

  const registry = new LocalAiWatcherRegistry({
    debounceMs: 250,
    maxPendingChanges: changedFileCount,
    maxConcurrentReconciliations: repoCount,
    watchFactory: (repoRoot, listener) => {
      listeners.set(repoRoot, listener);
      return { close: () => listeners.delete(repoRoot) };
    },
    setTimeoutFn: (callback) => {
      const handle = { id: `timer-${sequence++}`, callback, unref: () => {} };
      scheduled.set(handle.id, handle);
      return handle;
    },
    clearTimeoutFn: (handle) => scheduled.delete(handle.id),
    clock: () => new Date(Date.parse("2026-05-20T01:00:00Z") + sequence++),
    reconcileRepository: async (input) => {
      reconciliations.push({
        repoIdentityHash: input.repoIdentityHash,
        changedPathCount: input.changedPaths.length
      });
      return {
        status: "ok",
        changedFiles: input.changedPaths.map((repoRelativePath) => ({ repoRelativePath })),
        diffCandidates: input.changedPaths.map((repoRelativePath) => ({ repoRelativePath }))
      };
    }
  });

  for (let index = 0; index < repoCount; index += 1) {
    registry.updateRepository({
      repoIdentityHash: `repo-perf-${index}`,
      repositoryDisplayLabel: `perf/repo-${index}`,
      repoRoot: `/tmp/learnloop-perf-${index}`,
      status: "approved"
    });
  }

  const filesPerRepo = Math.ceil(changedFileCount / repoCount);
  for (let fileIndex = 0; fileIndex < changedFileCount; fileIndex += 1) {
    const repoIndex = fileIndex % repoCount;
    const listener = listeners.get(`/tmp/learnloop-perf-${repoIndex}`);
    const repoFileIndex = Math.floor(fileIndex / repoCount);
    for (let burst = 0; burst < saveBurstCount; burst += 1) {
      listener("change", `src/file-${repoFileIndex}.ts`);
    }
  }

  for (const handle of [...scheduled.values()]) {
    handle.callback();
  }
  await registry.drainReconciliations();

  const watchers = registry.list();
  const maxSettledChangeCount = Math.max(...watchers.map((watcher) => watcher.settledChangeCount));
  const maxPendingChangeCount = Math.max(...watchers.map((watcher) => watcher.pendingChangeCount));
  const totalDroppedEvents = watchers.reduce((sum, watcher) => sum + watcher.droppedEventCount, 0);
  const estimatedGitCommandCount = reconciliations.length * 2;
  const summary = {
    repoCount,
    changedFileCount,
    saveBurstCount,
    eventCount: changedFileCount * saveBurstCount,
    watcherCount: watchers.length,
    reconciliationCount: reconciliations.length,
    sessionCount: reconciliations.length,
    maxSettledChangeCount,
    maxPendingChangeCount,
    totalDroppedEvents,
    estimatedGitCommandCount,
    commandBudget: repoCount * 2,
    filesPerRepo
  };

  assertPerformanceSummary(summary);
  return summary;
}

export function assertPerformanceSummary(summary) {
  if (summary.watcherCount !== summary.repoCount) throw new Error("watcher count must match approved repo count");
  if (summary.reconciliationCount !== summary.repoCount) throw new Error("expected one reconciliation per repo");
  if (summary.sessionCount !== summary.repoCount) throw new Error("expected one final session per settled repo window");
  if (summary.maxPendingChangeCount !== 0) throw new Error("pending changes must be empty after reconciliation");
  if (summary.maxSettledChangeCount > summary.filesPerRepo) throw new Error("settled changes must stay bounded per repo");
  if (summary.totalDroppedEvents !== 0) throw new Error("performance gate must not drop events at this scale");
  if (summary.estimatedGitCommandCount > summary.commandBudget) throw new Error("estimated Git command count exceeded budget");
}

function positiveInteger(value, fallback) {
  return Number.isFinite(value) ? Math.max(1, Math.round(value)) : fallback;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  runWatcherPerformanceGate()
    .then((summary) => {
      process.stdout.write(`${JSON.stringify(summary, null, 2)}\n`);
    })
    .catch((error) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    });
}
