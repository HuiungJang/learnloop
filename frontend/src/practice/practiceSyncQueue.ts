import type { PracticeAttemptSyncRequest } from "../api/client";
import type { PracticeDraft } from "./practiceStorage";

export type PracticeSyncStatus = "local_only" | "syncing" | "synced" | "conflict" | "failed";

export type PracticeSyncQueueItem = {
  userId: string;
  problemId: string;
  assetRevision: string;
  clientAttemptId: string;
  request: PracticeAttemptSyncRequest;
  status: PracticeSyncStatus;
  attempts: number;
  updatedAt: string;
  nextAttemptAt: string;
  lastError: string | null;
};

type PracticeSyncQueueStore = {
  version: 1;
  items: PracticeSyncQueueItem[];
};

const STORAGE_KEY = "learnloop:practice-sync-queue:v1";
const MAX_QUEUE_ITEMS = 50;
const MIN_RETRY_MS = 1_000;
const MAX_RETRY_MS = 30_000;

export function buildDraftSyncRequest(draft: PracticeDraft): PracticeAttemptSyncRequest {
  return {
    clientAttemptId: draft.clientAttemptId,
    assetRevision: draft.assetRevision,
    language: inferLanguage(draft.files[0]?.path),
    intent: "draft",
    files: draft.files.map((file) => ({ path: file.path, content: file.content })),
    localUpdatedAt: draft.updatedAt
  };
}

export function enqueuePracticeSync(draft: PracticeDraft, storage = browserStorage()): PracticeSyncQueueItem {
  const now = new Date().toISOString();
  const item: PracticeSyncQueueItem = {
    userId: draft.userId,
    problemId: draft.problemId,
    assetRevision: draft.assetRevision,
    clientAttemptId: draft.clientAttemptId,
    request: buildDraftSyncRequest(draft),
    status: "local_only",
    attempts: 0,
    updatedAt: now,
    nextAttemptAt: now,
    lastError: null
  };
  return upsertQueueItem(item, storage);
}

export function markPracticeSyncing(item: PracticeSyncQueueItem, storage = browserStorage()): PracticeSyncQueueItem {
  return upsertQueueItem({ ...item, status: "syncing", updatedAt: new Date().toISOString(), lastError: null }, storage);
}

export function markPracticeSynced(item: PracticeSyncQueueItem, storage = browserStorage()): PracticeSyncQueueItem {
  return upsertQueueItem({ ...item, status: "synced", updatedAt: new Date().toISOString(), lastError: null }, storage);
}

export function markPracticeConflict(
  item: PracticeSyncQueueItem,
  message: string,
  storage = browserStorage()
): PracticeSyncQueueItem {
  return upsertQueueItem({ ...item, status: "conflict", updatedAt: new Date().toISOString(), lastError: message }, storage);
}

export function markPracticeFailed(
  item: PracticeSyncQueueItem,
  message: string,
  storage = browserStorage(),
  now = new Date()
): PracticeSyncQueueItem {
  const attempts = item.attempts + 1;
  return upsertQueueItem(
    {
      ...item,
      status: "failed",
      attempts,
      updatedAt: now.toISOString(),
      nextAttemptAt: new Date(now.getTime() + retryDelayMs(attempts)).toISOString(),
      lastError: message
    },
    storage
  );
}

export function listDuePracticeSyncItems(storage = browserStorage(), now = new Date()): PracticeSyncQueueItem[] {
  return readStore(storage).items.filter((item) => {
    if (item.status === "synced" || item.status === "conflict" || item.status === "syncing") return false;
    return Date.parse(item.nextAttemptAt) <= now.getTime();
  });
}

export function loadPracticeSyncQueue(storage = browserStorage()): PracticeSyncQueueItem[] {
  return readStore(storage).items;
}

export function practiceSyncQueueStorageKey(): string {
  return STORAGE_KEY;
}

function upsertQueueItem(item: PracticeSyncQueueItem, storage: Storage | null): PracticeSyncQueueItem {
  const normalized = normalizeItem(item);
  const store = readStore(storage);
  const items = [normalized, ...store.items.filter((candidate) => !sameQueueItem(candidate, normalized))]
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, MAX_QUEUE_ITEMS);
  writeStore({ version: 1, items }, storage);
  return normalized;
}

function readStore(storage: Storage | null): PracticeSyncQueueStore {
  if (storage === null) return emptyStore();
  const raw = storage.getItem(STORAGE_KEY);
  if (raw === null) return emptyStore();

  try {
    const parsed = JSON.parse(raw) as Partial<PracticeSyncQueueStore>;
    if (parsed.version !== 1 || !Array.isArray(parsed.items)) return emptyStore();
    return { version: 1, items: parsed.items.map(normalizeItem).slice(0, MAX_QUEUE_ITEMS) };
  } catch {
    storage.removeItem(STORAGE_KEY);
    return emptyStore();
  }
}

function writeStore(store: PracticeSyncQueueStore, storage: Storage | null): void {
  if (storage === null) return;
  storage.setItem(STORAGE_KEY, JSON.stringify(store));
}

function normalizeItem(item: PracticeSyncQueueItem): PracticeSyncQueueItem {
  const now = new Date().toISOString();
  return {
    userId: String(item.userId),
    problemId: String(item.problemId),
    assetRevision: String(item.assetRevision),
    clientAttemptId: String(item.clientAttemptId),
    request: item.request,
    status: isPracticeSyncStatus(item.status) ? item.status : "local_only",
    attempts: Number.isFinite(item.attempts) ? Math.max(0, item.attempts) : 0,
    updatedAt: typeof item.updatedAt === "string" && item.updatedAt.length > 0 ? item.updatedAt : now,
    nextAttemptAt: typeof item.nextAttemptAt === "string" && item.nextAttemptAt.length > 0 ? item.nextAttemptAt : now,
    lastError: typeof item.lastError === "string" ? item.lastError : null
  };
}

function sameQueueItem(a: PracticeSyncQueueItem, b: PracticeSyncQueueItem): boolean {
  return a.userId === b.userId && a.problemId === b.problemId && a.clientAttemptId === b.clientAttemptId;
}

function retryDelayMs(attempts: number): number {
  return Math.min(MAX_RETRY_MS, MIN_RETRY_MS * 2 ** Math.max(0, attempts - 1));
}

function isPracticeSyncStatus(status: string): status is PracticeSyncStatus {
  return ["local_only", "syncing", "synced", "conflict", "failed"].includes(status);
}

function inferLanguage(path: string | undefined): string {
  if (path?.endsWith(".kt")) return "kotlin";
  if (path?.endsWith(".java")) return "java";
  return "typescript";
}

function browserStorage(): Storage | null {
  if (typeof window === "undefined") return null;
  return window.localStorage;
}

function emptyStore(): PracticeSyncQueueStore {
  return { version: 1, items: [] };
}
