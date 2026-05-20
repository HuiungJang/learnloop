import { watch } from "node:fs";
import path from "node:path";

const APPROVED_STATUS = "approved";
const REPOSITORY_STATUSES = new Set(["approved", "revoked", "always_ignored", "missing"]);
const REPO_IDENTITY_PATTERN = /^[A-Za-z0-9._:-]{3,128}$/;
const DEFAULT_DEBOUNCE_MS = 750;
const MIN_DEBOUNCE_MS = 250;
const MAX_DEBOUNCE_MS = 5000;
const DEFAULT_MAX_PENDING_CHANGES = 1000;
const DEFAULT_MAX_CONCURRENT_RECONCILIATIONS = 2;

export class LocalAiWatcherRegistry {
  constructor(options = {}) {
    this.watchFactory = options.watchFactory ?? defaultWatchFactory;
    this.clock = options.clock ?? (() => new Date());
    this.debounceMs = normalizeDebounceMs(options.debounceMs);
    this.maxPendingChanges = normalizePositiveInteger(options.maxPendingChanges, DEFAULT_MAX_PENDING_CHANGES);
    this.maxConcurrentReconciliations = normalizePositiveInteger(
      options.maxConcurrentReconciliations,
      DEFAULT_MAX_CONCURRENT_RECONCILIATIONS
    );
    this.setTimeout = options.setTimeoutFn ?? setTimeout;
    this.clearTimeout = options.clearTimeoutFn ?? clearTimeout;
    this.reconcileRepository = options.reconcileRepository ?? null;
    this.reconciliationPromises = new Set();
    this.registrations = new Map();
    this.activeReconciliations = 0;
  }

  updateRepository(value) {
    const input = normalizeRepository(value);
    const existing = this.registrations.get(input.repoIdentityHash);
    const now = this.clock().toISOString();

    if (input.status !== APPROVED_STATUS) {
      if (existing) {
        this.stopActiveWatcher(existing);
      }
      const registration = {
        ...baseRegistration(input, now),
        debounceMs: this.debounceMs,
        state: "stopped",
        reason: `repository_${input.status}`,
        startedAt: existing?.startedAt ?? null,
        stoppedAt: now,
        eventCount: existing?.eventCount ?? 0,
        lastEventAt: existing?.lastEventAt ?? null,
        ...changeState(existing),
        watcher: null
      };
      this.registrations.set(input.repoIdentityHash, registration);
      return publicRegistration(registration);
    }

    if (!input.repoRoot) {
      if (existing) {
        this.stopActiveWatcher(existing);
      }
      const registration = {
        ...baseRegistration(input, now),
        debounceMs: this.debounceMs,
        state: "unavailable",
        reason: "missing_repo_root",
        startedAt: existing?.startedAt ?? null,
        stoppedAt: now,
        eventCount: existing?.eventCount ?? 0,
        lastEventAt: existing?.lastEventAt ?? null,
        ...changeState(existing),
        watcher: null
      };
      this.registrations.set(input.repoIdentityHash, registration);
      return publicRegistration(registration);
    }

    if (existing?.watcher && existing.repoRoot === input.repoRoot && existing.state === "active") {
      existing.repositoryDisplayLabel = input.repositoryDisplayLabel;
      existing.updatedAt = now;
      return publicRegistration(existing);
    }

    if (existing) {
      this.stopActiveWatcher(existing);
    }

    const registration = {
      ...baseRegistration(input, now),
      debounceMs: this.debounceMs,
      state: "active",
      reason: null,
      startedAt: now,
      stoppedAt: null,
      eventCount: existing?.eventCount ?? 0,
      lastEventAt: existing?.lastEventAt ?? null,
      ...changeState(existing),
      watcher: null
    };

    try {
      registration.watcher = this.watchFactory(input.repoRoot, (eventType, filename) => {
        const observedAt = this.clock().toISOString();
        registration.eventCount += 1;
        registration.lastEventAt = observedAt;
        this.recordFileEvent(registration, eventType, filename, observedAt);
      });
    } catch {
      registration.state = "degraded";
      registration.reason = "watch_start_failed";
      registration.stoppedAt = now;
      registration.watcher = null;
    }

    this.registrations.set(input.repoIdentityHash, registration);
    return publicRegistration(registration);
  }

  list() {
    return [...this.registrations.values()].map(publicRegistration);
  }

  settledChangesFor(repoIdentityHash) {
    const registration = this.registrations.get(repoIdentityHash);
    return registration ? [...registration.settledChanges] : [];
  }

  tryStartReconciliation(repoIdentityHash) {
    const registration = this.registrations.get(repoIdentityHash);
    if (
      !registration ||
      registration.state === "stopped" ||
      registration.state === "unavailable" ||
      registration.reconciliationActive ||
      this.activeReconciliations >= this.maxConcurrentReconciliations
    ) {
      return false;
    }
    registration.reconciliationActive = true;
    this.activeReconciliations += 1;
    return true;
  }

  finishReconciliation(repoIdentityHash, options = {}) {
    const registration = this.registrations.get(repoIdentityHash);
    if (!registration?.reconciliationActive) return;
    registration.reconciliationActive = false;
    this.activeReconciliations = Math.max(0, this.activeReconciliations - 1);
    if (options.fullReconciliationCompleted === true) {
      registration.pendingChanges.clear();
      registration.settledChanges = [];
      registration.needsFullReconciliation = false;
      if (registration.state === "degraded" && registration.reason === "pending_queue_full") {
        registration.state = "active";
        registration.reason = null;
      }
    }
  }

  async drainReconciliations() {
    await Promise.allSettled([...this.reconciliationPromises]);
  }

  counts() {
    return this.list().reduce(
      (counts, registration) => ({
        ...counts,
        [registration.state]: counts[registration.state] + 1
      }),
      { active: 0, stopped: 0, degraded: 0, unavailable: 0 }
    );
  }

  stopAll() {
    const now = this.clock().toISOString();
    for (const registration of this.registrations.values()) {
      if (registration.watcher || registration.debounceTimer) {
        this.stopActiveWatcher(registration);
        registration.state = "stopped";
        registration.reason = "companion_stopping";
        registration.stoppedAt = now;
        registration.updatedAt = now;
      }
    }
  }

  stopActiveWatcher(registration) {
    if (registration.watcher) {
      stopWatcher(registration.watcher);
      registration.watcher = null;
    }
    if (registration.debounceTimer) {
      this.clearTimeout(registration.debounceTimer);
      registration.debounceTimer = null;
    }
    if (registration.reconciliationActive) {
      registration.reconciliationActive = false;
      this.activeReconciliations = Math.max(0, this.activeReconciliations - 1);
    }
    registration.reconciliationQueued = false;
    registration.pendingChanges.clear();
  }

  recordFileEvent(registration, eventType, filename, changedAt) {
    const repoRelativePath = normalizeEventPath(filename);
    if (!repoRelativePath) return;
    if (!registration.pendingChanges.has(repoRelativePath) && registration.pendingChanges.size >= this.maxPendingChanges) {
      registration.droppedEventCount += 1;
      registration.needsFullReconciliation = true;
      registration.state = "degraded";
      registration.reason = "pending_queue_full";
      return;
    }
    registration.pendingChanges.set(repoRelativePath, {
      repoRelativePath,
      eventType: eventType === "rename" ? "rename" : "change",
      changedAt
    });
    scheduleDebounce(this, registration);
  }

  settleChanges(registration) {
    registration.debounceTimer = null;
    if (registration.pendingChanges.size === 0) return;
    registration.settledChanges = [...registration.pendingChanges.values()].sort((left, right) =>
      left.repoRelativePath.localeCompare(right.repoRelativePath)
    );
    registration.pendingChanges.clear();
    registration.lastSettledAt = this.clock().toISOString();
    registration.settledBatchCount += 1;
    this.requestReconciliation(registration);
  }

  requestReconciliation(registration) {
    if (!this.reconcileRepository) return;
    if (!this.tryStartReconciliation(registration.repoIdentityHash)) {
      registration.reconciliationQueued = true;
      return;
    }

    const needsFullReconciliation = registration.needsFullReconciliation;
    const payload = {
      repoIdentityHash: registration.repoIdentityHash,
      repoRoot: registration.repoRoot,
      changedPaths: needsFullReconciliation ? [] : registration.settledChanges.map((change) => change.repoRelativePath),
      needsFullReconciliation
    };
    const promise =
      Promise.resolve()
        .then(() => this.reconcileRepository(payload))
        .then((result) => {
          registration.lastReconciliationAt = this.clock().toISOString();
          registration.lastReconciliationStatus = result?.status ?? "ok";
          registration.lastReconciliationChangedFileCount = result?.changedFiles?.length ?? 0;
          registration.lastReconciliationDiffCandidateCount = result?.diffCandidates?.length ?? 0;
          if (result?.status === "degraded") {
            registration.state = "degraded";
            registration.reason = result.reason ?? "git_reconciliation_failed";
          }
          this.finishReconciliation(registration.repoIdentityHash, {
            fullReconciliationCompleted: result?.status === "ok" && needsFullReconciliation
          });
        })
        .catch(() => {
          registration.lastReconciliationAt = this.clock().toISOString();
          registration.lastReconciliationStatus = "failed";
          registration.state = "degraded";
          registration.reason = "git_reconciliation_failed";
          this.finishReconciliation(registration.repoIdentityHash);
        })
        .finally(() => {
          this.reconciliationPromises.delete(promise);
          if (registration.reconciliationQueued) {
            registration.reconciliationQueued = false;
            this.requestReconciliation(registration);
          }
        });
    this.reconciliationPromises.add(promise);
  }
}

function normalizeRepository(value) {
  const input = typeof value === "object" && value !== null ? value : {};
  const repoIdentityHash = safeToken(input.repoIdentityHash);
  if (!repoIdentityHash || !REPO_IDENTITY_PATTERN.test(repoIdentityHash)) {
    throw new Error("repoIdentityHash is required");
  }
  const status = safeToken(input.status) ?? APPROVED_STATUS;
  if (!REPOSITORY_STATUSES.has(status)) {
    throw new Error("repository status is invalid");
  }
  return {
    repoIdentityHash,
    repositoryDisplayLabel: safeRepositoryDisplayLabel(input.repositoryDisplayLabel, repoIdentityHash),
    repoRoot: safeAbsolutePath(input.repoRoot),
    status
  };
}

function baseRegistration(input, now) {
  return {
    repoIdentityHash: input.repoIdentityHash,
    repositoryDisplayLabel: input.repositoryDisplayLabel,
    repoRoot: input.repoRoot,
    repositoryStatus: input.status,
    updatedAt: now
  };
}

function changeState(existing) {
  return {
    pendingChanges: existing?.pendingChanges ?? new Map(),
    settledChanges: existing?.settledChanges ?? [],
    debounceTimer: null,
    lastSettledAt: existing?.lastSettledAt ?? null,
    settledBatchCount: existing?.settledBatchCount ?? 0,
    droppedEventCount: existing?.droppedEventCount ?? 0,
    needsFullReconciliation: existing?.needsFullReconciliation ?? false,
    reconciliationActive: existing?.reconciliationActive ?? false,
    reconciliationQueued: existing?.reconciliationQueued ?? false,
    lastReconciliationAt: existing?.lastReconciliationAt ?? null,
    lastReconciliationStatus: existing?.lastReconciliationStatus ?? null,
    lastReconciliationChangedFileCount: existing?.lastReconciliationChangedFileCount ?? 0,
    lastReconciliationDiffCandidateCount: existing?.lastReconciliationDiffCandidateCount ?? 0
  };
}

function publicRegistration(registration) {
  return {
    repoIdentityHash: registration.repoIdentityHash,
    repositoryDisplayLabel: registration.repositoryDisplayLabel,
    repositoryStatus: registration.repositoryStatus,
    state: registration.state,
    reason: registration.reason,
    startedAt: registration.startedAt,
    stoppedAt: registration.stoppedAt,
    updatedAt: registration.updatedAt,
    eventCount: registration.eventCount,
    lastEventAt: registration.lastEventAt,
    pendingChangeCount: registration.pendingChanges.size,
    settledChangeCount: registration.settledChanges.length,
    lastSettledAt: registration.lastSettledAt,
    settledBatchCount: registration.settledBatchCount,
    droppedEventCount: registration.droppedEventCount,
    needsFullReconciliation: registration.needsFullReconciliation,
    reconciliationActive: registration.reconciliationActive,
    reconciliationQueued: registration.reconciliationQueued,
    lastReconciliationAt: registration.lastReconciliationAt,
    lastReconciliationStatus: registration.lastReconciliationStatus,
    lastReconciliationChangedFileCount: registration.lastReconciliationChangedFileCount,
    lastReconciliationDiffCandidateCount: registration.lastReconciliationDiffCandidateCount,
    debounceMs: registration.watcher ? registration.debounceMs ?? null : null
  };
}

function defaultWatchFactory(repoRoot, listener) {
  const options = process.platform === "darwin" || process.platform === "win32"
    ? { persistent: false, recursive: true }
    : { persistent: false };
  return watch(repoRoot, options, listener);
}

function stopWatcher(watcher) {
  try {
    watcher.close();
  } catch {
    // Watcher shutdown should not block repository state changes.
  }
}

function scheduleDebounce(registry, registration) {
  if (registration.debounceTimer) {
    registry.clearTimeout(registration.debounceTimer);
  }
  registration.debounceTimer = registry.setTimeout(() => {
    registry.settleChanges(registration);
  }, registry.debounceMs);
  registration.debounceTimer?.unref?.();
}

function normalizeDebounceMs(value) {
  if (!Number.isFinite(value)) return DEFAULT_DEBOUNCE_MS;
  return Math.min(MAX_DEBOUNCE_MS, Math.max(MIN_DEBOUNCE_MS, Math.round(value)));
}

function normalizePositiveInteger(value, fallback) {
  if (!Number.isFinite(value)) return fallback;
  return Math.max(1, Math.round(value));
}

function normalizeEventPath(value) {
  if (value === undefined || value === null) return null;
  const raw = Buffer.isBuffer(value) ? value.toString("utf8") : String(value);
  if (raw.length === 0 || raw.includes("\0")) return null;
  const normalized = path.posix.normalize(raw.replace(/\\/g, "/"));
  if (normalized === "." || normalized === ".." || normalized.startsWith("../") || path.posix.isAbsolute(normalized)) {
    return null;
  }
  return normalized;
}

function safeToken(value) {
  return typeof value === "string" && value.trim().length > 0 ? value.trim().slice(0, 128) : null;
}

function safeString(value, maxLength) {
  return typeof value === "string" && value.trim().length > 0 ? value.trim().slice(0, maxLength) : null;
}

function safeRepositoryDisplayLabel(value, fallback) {
  const label = safeString(value, 120);
  if (!label) return fallback;
  if (/^(?:\/|~\/|[A-Za-z]:[\\/]|\\\\)/.test(label)) {
    return path.basename(label);
  }
  return label;
}

function safeAbsolutePath(value) {
  if (typeof value !== "string" || value.trim().length === 0) return null;
  const trimmed = value.trim();
  if (!path.isAbsolute(trimmed)) return null;
  const resolved = path.resolve(trimmed);
  return path.isAbsolute(resolved) ? resolved : null;
}
