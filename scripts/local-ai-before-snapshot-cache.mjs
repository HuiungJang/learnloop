import { createHash } from "node:crypto";

const DEFAULT_MAX_ENTRIES_PER_REPO = 100;
const DEFAULT_MAX_BYTES_PER_REPO = 5 * 1024 * 1024;
const DEFAULT_MAX_SNAPSHOT_BYTES = 200 * 1024;

export class BeforeSnapshotCache {
  constructor(options = {}) {
    this.maxEntriesPerRepo = positiveInteger(options.maxEntriesPerRepo, DEFAULT_MAX_ENTRIES_PER_REPO);
    this.maxBytesPerRepo = positiveInteger(options.maxBytesPerRepo, DEFAULT_MAX_BYTES_PER_REPO);
    this.maxSnapshotBytes = positiveInteger(options.maxSnapshotBytes, DEFAULT_MAX_SNAPSHOT_BYTES);
    this.repos = new Map();
  }

  markPreExistingDirty(repoIdentityHash, files) {
    const repo = this.repo(repoIdentityHash);
    for (const file of files) {
      putEntry(repo, {
        repoRelativePath: file.repoRelativePath,
        status: "unavailable",
        reason: "pre_existing_dirty",
        contentText: null,
        sizeBytes: null,
        contentHash: null
      });
    }
    enforceBounds(repo, this.maxEntriesPerRepo, this.maxBytesPerRepo);
  }

  async captureSnapshots({ repoIdentityHash, files, readSnapshot }) {
    const repo = this.repo(repoIdentityHash);
    const snapshots = [];
    for (const file of files) {
      const existing = repo.entries.get(file.repoRelativePath);
      if (existing) {
        touchEntry(repo, file.repoRelativePath);
        snapshots.push(publicEntry(existing));
        continue;
      }

      const snapshot = await readSnapshot(file.repoRelativePath);
      const entry = snapshot?.ok
        ? entryFromSnapshot(file.repoRelativePath, snapshot.content ?? "", this.maxSnapshotBytes)
        : {
            repoRelativePath: file.repoRelativePath,
            status: "unavailable",
            reason: "snapshot_unavailable",
            contentText: null,
            sizeBytes: null,
            contentHash: null
          };
      putEntry(repo, entry);
      enforceBounds(repo, this.maxEntriesPerRepo, this.maxBytesPerRepo);
      const cached = repo.entries.get(file.repoRelativePath);
      if (cached) snapshots.push(publicEntry(cached));
    }
    return snapshots;
  }

  list(repoIdentityHash) {
    return [...this.repo(repoIdentityHash).entries.values()].map(publicEntry);
  }

  repo(repoIdentityHash) {
    const key = String(repoIdentityHash || "unknown");
    if (!this.repos.has(key)) {
      this.repos.set(key, { entries: new Map(), contentBytes: 0 });
    }
    return this.repos.get(key);
  }
}

function entryFromSnapshot(repoRelativePath, content, maxSnapshotBytes) {
  const sizeBytes = Buffer.byteLength(content, "utf8");
  const contentHash = createHash("sha256").update(content).digest("hex");
  if (sizeBytes > maxSnapshotBytes) {
    return {
      repoRelativePath,
      status: "metadata_only",
      reason: "oversized_before_snapshot",
      contentText: null,
      sizeBytes,
      contentHash
    };
  }
  return {
    repoRelativePath,
    status: "captured",
    reason: null,
    contentText: content,
    sizeBytes,
    contentHash
  };
}

function putEntry(repo, entry) {
  const existing = repo.entries.get(entry.repoRelativePath);
  if (existing) repo.contentBytes -= existing.contentText === null ? 0 : existing.sizeBytes ?? 0;
  repo.entries.delete(entry.repoRelativePath);
  repo.entries.set(entry.repoRelativePath, entry);
  repo.contentBytes += entry.contentText === null ? 0 : entry.sizeBytes ?? 0;
}

function touchEntry(repo, repoRelativePath) {
  const existing = repo.entries.get(repoRelativePath);
  if (!existing) return;
  repo.entries.delete(repoRelativePath);
  repo.entries.set(repoRelativePath, existing);
}

function enforceBounds(repo, maxEntries, maxBytes) {
  while (repo.entries.size > maxEntries || repo.contentBytes > maxBytes) {
    const oldestKey = repo.entries.keys().next().value;
    const oldest = repo.entries.get(oldestKey);
    repo.contentBytes -= oldest.contentText === null ? 0 : oldest.sizeBytes ?? 0;
    repo.entries.delete(oldestKey);
  }
}

function publicEntry(entry) {
  return { ...entry };
}

function positiveInteger(value, fallback) {
  return Number.isFinite(value) ? Math.max(1, Math.round(value)) : fallback;
}
