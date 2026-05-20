import { watch } from "node:fs";
import path from "node:path";

const APPROVED_STATUS = "approved";
const REPOSITORY_STATUSES = new Set(["approved", "revoked", "always_ignored", "missing"]);
const REPO_IDENTITY_PATTERN = /^[A-Za-z0-9._:-]{3,128}$/;

export class LocalAiWatcherRegistry {
  constructor(options = {}) {
    this.watchFactory = options.watchFactory ?? defaultWatchFactory;
    this.clock = options.clock ?? (() => new Date());
    this.registrations = new Map();
  }

  updateRepository(value) {
    const input = normalizeRepository(value);
    const existing = this.registrations.get(input.repoIdentityHash);
    const now = this.clock().toISOString();

    if (input.status !== APPROVED_STATUS) {
      if (existing?.watcher) {
        stopWatcher(existing.watcher);
      }
      const registration = {
        ...baseRegistration(input, now),
        state: "stopped",
        reason: `repository_${input.status}`,
        startedAt: existing?.startedAt ?? null,
        stoppedAt: now,
        eventCount: existing?.eventCount ?? 0,
        lastEventAt: existing?.lastEventAt ?? null,
        watcher: null
      };
      this.registrations.set(input.repoIdentityHash, registration);
      return publicRegistration(registration);
    }

    if (!input.repoRoot) {
      if (existing?.watcher) {
        stopWatcher(existing.watcher);
      }
      const registration = {
        ...baseRegistration(input, now),
        state: "unavailable",
        reason: "missing_repo_root",
        startedAt: existing?.startedAt ?? null,
        stoppedAt: now,
        eventCount: existing?.eventCount ?? 0,
        lastEventAt: existing?.lastEventAt ?? null,
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

    if (existing?.watcher) {
      stopWatcher(existing.watcher);
    }

    const registration = {
      ...baseRegistration(input, now),
      state: "active",
      reason: null,
      startedAt: now,
      stoppedAt: null,
      eventCount: existing?.eventCount ?? 0,
      lastEventAt: existing?.lastEventAt ?? null,
      watcher: null
    };

    try {
      registration.watcher = this.watchFactory(input.repoRoot, () => {
        registration.eventCount += 1;
        registration.lastEventAt = this.clock().toISOString();
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
      if (registration.watcher) {
        stopWatcher(registration.watcher);
        registration.watcher = null;
        registration.state = "stopped";
        registration.reason = "companion_stopping";
        registration.stoppedAt = now;
        registration.updatedAt = now;
      }
    }
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
    lastEventAt: registration.lastEventAt
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
