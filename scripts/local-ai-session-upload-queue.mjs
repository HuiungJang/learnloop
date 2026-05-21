import { createHash } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const DEFAULT_STORE_FILE = path.join(os.homedir(), ".learnloop", "local-ai-session-upload-queue.json");
const DEFAULT_MAX_ITEMS = 50;
const DEFAULT_MAX_ATTEMPTS = 5;
const DEFAULT_RETRY_BASE_MS = 1_000;
const DEFAULT_RETRY_MAX_MS = 30_000;

export class LocalAiSessionUploadQueue {
  constructor(options = {}) {
    this.storeFile = options.storeFile ?? DEFAULT_STORE_FILE;
    this.maxItems = positiveInteger(options.maxItems, DEFAULT_MAX_ITEMS);
    this.maxAttempts = positiveInteger(options.maxAttempts, DEFAULT_MAX_ATTEMPTS);
    this.retryBaseMs = positiveInteger(options.retryBaseMs, DEFAULT_RETRY_BASE_MS);
    this.retryMaxMs = positiveInteger(options.retryMaxMs, DEFAULT_RETRY_MAX_MS);
    this.now = options.now ?? (() => new Date());
    this.uploader = options.uploader ?? null;
    this.items = [];
    this.discardedCount = 0;
  }

  static async open(options = {}) {
    const queue = new LocalAiSessionUploadQueue(options);
    await queue.load();
    return queue;
  }

  async load() {
    const store = await readStore(this.storeFile);
    this.items = store.items.map(normalizeItem).filter((item) => item !== null).slice(0, this.maxItems);
  }

  async enqueue(payload) {
    const item = queueItemFromPayload(payload, this.nowDate());
    this.items = [item, ...this.items.filter((candidate) => candidate.id !== item.id)];
    this.enforceMaxItems();
    await this.persist();
    return itemMetadata(item);
  }

  async uploadDue(options = {}) {
    const uploader = options.uploader ?? this.uploader;
    if (typeof uploader !== "function") throw new Error("uploader is required");

    const now = this.nowDate();
    const due = this.items
      .filter((item) => Date.parse(item.nextAttemptAt) <= now.getTime())
      .sort((left, right) => left.nextAttemptAt.localeCompare(right.nextAttemptAt));
    const result = { uploaded: [], failed: [], discarded: [] };

    for (const item of due) {
      try {
        await uploader(item.payload, itemMetadata(item));
        this.items = this.items.filter((candidate) => candidate.id !== item.id);
        result.uploaded.push(item.id);
      } catch (error) {
        const attempts = item.attempts + 1;
        if (attempts >= this.maxAttempts) {
          this.items = this.items.filter((candidate) => candidate.id !== item.id);
          this.discardedCount += 1;
          result.discarded.push(item.id);
          continue;
        }
        item.status = "failed";
        item.attempts = attempts;
        item.updatedAt = now.toISOString();
        item.nextAttemptAt = new Date(now.getTime() + retryDelayMs(attempts, this.retryBaseMs, this.retryMaxMs)).toISOString();
        item.lastError = sanitizeError(error);
        result.failed.push(item.id);
      }
    }

    if (due.length > 0) await this.persist();
    return result;
  }

  async cancelRepository(repoIdentityHash) {
    const before = this.items.length;
    this.items = this.items.filter((item) => item.repoIdentityHash !== repoIdentityHash);
    const removed = before - this.items.length;
    if (removed > 0) await this.persist();
    return removed;
  }

  listMetadata() {
    return this.items.map(itemMetadata);
  }

  stats() {
    return {
      queued: this.items.length,
      discardedCount: this.discardedCount,
      nextAttemptAt: this.items
        .map((item) => item.nextAttemptAt)
        .sort()[0] ?? null
    };
  }

  async persist() {
    await writeStore(this.storeFile, { version: 1, items: this.items });
  }

  nowDate() {
    const value = this.now();
    if (value instanceof Date) return value;
    if (typeof value === "number") return new Date(value);
    return new Date(value);
  }

  enforceMaxItems() {
    this.items.sort((left, right) => right.queuedAt.localeCompare(left.queuedAt));
    if (this.items.length <= this.maxItems) return;
    this.discardedCount += this.items.length - this.maxItems;
    this.items = this.items.slice(0, this.maxItems);
  }
}

export async function uploadLocalAiSession(payload, options = {}) {
  const backendUrl = requiredString(options.backendUrl, "backendUrl").replace(/\/+$/, "");
  const token = requiredString(options.authToken, "authToken");
  const fetchFn = options.fetchFn ?? fetch;
  const response = await fetchFn(`${backendUrl}/api/ingest/local-ai-session`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${token}`
    },
    body: JSON.stringify(payload)
  });
  if (!response.ok) {
    throw new Error(`local session upload failed with ${response.status}`);
  }
  return await response.json();
}

function queueItemFromPayload(payload, now) {
  const input = requireObject(payload, "payload");
  const idSeed = input.idempotencyKey ?? JSON.stringify(input);
  const id = sha256Hex(`local-session-upload:${idSeed}`);
  const artifacts = Array.isArray(input.artifacts) ? input.artifacts : [];
  return {
    id,
    status: "queued",
    attempts: 0,
    queuedAt: now.toISOString(),
    updatedAt: now.toISOString(),
    nextAttemptAt: now.toISOString(),
    lastError: null,
    repoIdentityHash: safeString(input.repoIdentityHash, 128) ?? "unknown",
    repositoryDisplayLabel: safeString(input.repositoryDisplayLabel, 240),
    title: safeString(input.title, 180),
    sourceKind: safeString(input.sourceKind, 80) ?? "local_ai_session",
    idempotencyKeyHash: sha256Hex(String(input.idempotencyKey ?? id)),
    payloadHash: sha256Hex(JSON.stringify(input)),
    artifactCount: artifacts.length,
    codeArtifactCount: artifacts.filter((artifact) => artifact?.itemType !== "tool_event").length,
    toolArtifactCount: artifacts.filter((artifact) => artifact?.itemType === "tool_event").length,
    payload: input
  };
}

function itemMetadata(item) {
  return {
    id: item.id,
    status: item.status,
    attempts: item.attempts,
    queuedAt: item.queuedAt,
    updatedAt: item.updatedAt,
    nextAttemptAt: item.nextAttemptAt,
    lastError: item.lastError,
    repoIdentityHash: item.repoIdentityHash,
    repositoryDisplayLabel: item.repositoryDisplayLabel,
    title: item.title,
    sourceKind: item.sourceKind,
    idempotencyKeyHash: item.idempotencyKeyHash,
    payloadHash: item.payloadHash,
    artifactCount: item.artifactCount,
    codeArtifactCount: item.codeArtifactCount,
    toolArtifactCount: item.toolArtifactCount
  };
}

async function readStore(storeFile) {
  try {
    const parsed = JSON.parse(await readFile(storeFile, "utf8"));
    if (parsed?.version !== 1 || !Array.isArray(parsed.items)) return emptyStore();
    return { version: 1, items: parsed.items };
  } catch {
    return emptyStore();
  }
}

async function writeStore(storeFile, store) {
  await mkdir(path.dirname(storeFile), { recursive: true, mode: 0o700 });
  const temporaryFile = `${storeFile}.${process.pid}.tmp`;
  await writeFile(temporaryFile, `${JSON.stringify(store)}\n`, { mode: 0o600 });
  await rename(temporaryFile, storeFile);
}

function normalizeItem(value) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  if (typeof value.payload !== "object" || value.payload === null) return null;
  return {
    id: safeString(value.id, 128) ?? sha256Hex(JSON.stringify(value.payload)),
    status: value.status === "failed" ? "failed" : "queued",
    attempts: Number.isFinite(value.attempts) ? Math.max(0, Math.round(value.attempts)) : 0,
    queuedAt: safeIso(value.queuedAt) ?? new Date(0).toISOString(),
    updatedAt: safeIso(value.updatedAt) ?? new Date(0).toISOString(),
    nextAttemptAt: safeIso(value.nextAttemptAt) ?? new Date(0).toISOString(),
    lastError: safeString(value.lastError, 200),
    repoIdentityHash: safeString(value.repoIdentityHash, 128) ?? "unknown",
    repositoryDisplayLabel: safeString(value.repositoryDisplayLabel, 240),
    title: safeString(value.title, 180),
    sourceKind: safeString(value.sourceKind, 80) ?? "local_ai_session",
    idempotencyKeyHash: safeHash(value.idempotencyKeyHash) ?? sha256Hex(String(value.payload.idempotencyKey ?? "")),
    payloadHash: safeHash(value.payloadHash) ?? sha256Hex(JSON.stringify(value.payload)),
    artifactCount: nonNegativeInteger(value.artifactCount),
    codeArtifactCount: nonNegativeInteger(value.codeArtifactCount),
    toolArtifactCount: nonNegativeInteger(value.toolArtifactCount),
    payload: value.payload
  };
}

function retryDelayMs(attempts, baseMs, maxMs) {
  return Math.min(maxMs, baseMs * 2 ** Math.max(0, attempts - 1));
}

function sanitizeError(error) {
  return String(error?.message ?? error ?? "upload failed")
    .replace(/\bsk-[A-Za-z0-9_-]{8,}\b/g, "[redacted-token]")
    .replace(/(?:\/Users\/|~\/)[^\s"']+/g, "[local-path]")
    .slice(0, 200);
}

function safeIso(value) {
  if (typeof value !== "string" || value.trim() === "") return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function safeString(value, maxLength) {
  return typeof value === "string" && value.trim() !== "" ? value.trim().slice(0, maxLength) : null;
}

function safeHash(value) {
  return typeof value === "string" && /^[a-fA-F0-9]{64}$/.test(value) ? value.toLowerCase() : null;
}

function nonNegativeInteger(value) {
  return Number.isFinite(value) ? Math.max(0, Math.round(value)) : 0;
}

function positiveInteger(value, fallback) {
  return Number.isFinite(value) ? Math.max(1, Math.round(value)) : fallback;
}

function requiredString(value, field) {
  if (typeof value !== "string" || value.trim() === "") throw new Error(`${field} is required`);
  return value.trim();
}

function requireObject(value, field) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error(`${field} must be an object`);
  return value;
}

function emptyStore() {
  return { version: 1, items: [] };
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
