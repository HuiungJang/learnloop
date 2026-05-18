import type { PracticeAttemptFileRequest } from "../api/client";

export type PracticeDraftFile = PracticeAttemptFileRequest & {
  updatedAt: string;
};

export type PracticeDraft = {
  userId: string;
  problemId: string;
  assetRevision: string;
  clientAttemptId: string;
  files: PracticeDraftFile[];
  updatedAt: string;
};

export type PracticeDraftInput = {
  userId: string;
  problemId: string;
  assetRevision: string;
  files: PracticeAttemptFileRequest[];
  updatedAt?: string;
};

type PracticeDraftStore = {
  version: 1;
  drafts: PracticeDraft[];
};

const STORAGE_KEY = "learnloop:practice-drafts:v1";
const MAX_DRAFTS = 50;

export function ensurePracticeDraft(input: PracticeDraftInput, storage = browserStorage()): PracticeDraft {
  const existing = loadPracticeDraft(input, storage);
  if (existing !== null) {
    return savePracticeDraft(
      {
        ...existing,
        files: withFileTimestamps(input.files, input.updatedAt ?? nowIso()),
        updatedAt: input.updatedAt ?? nowIso()
      },
      storage
    );
  }

  return savePracticeDraft(
    {
      userId: input.userId,
      problemId: input.problemId,
      assetRevision: input.assetRevision,
      clientAttemptId: createClientAttemptId(),
      files: withFileTimestamps(input.files, input.updatedAt ?? nowIso()),
      updatedAt: input.updatedAt ?? nowIso()
    },
    storage
  );
}

export function loadPracticeDraft(
  scope: Pick<PracticeDraft, "userId" | "problemId" | "assetRevision">,
  storage = browserStorage()
): PracticeDraft | null {
  const store = readStore(storage);
  return store.drafts.find((draft) => matchesScope(draft, scope)) ?? null;
}

export function savePracticeDraft(draft: PracticeDraft, storage = browserStorage()): PracticeDraft {
  const normalized = normalizeDraft(draft);
  const store = readStore(storage);
  const drafts = [normalized, ...store.drafts.filter((candidate) => !matchesScope(candidate, normalized))]
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .slice(0, MAX_DRAFTS);
  writeStore({ version: 1, drafts }, storage);
  return normalized;
}

export function removePracticeDraft(
  scope: Pick<PracticeDraft, "userId" | "problemId" | "assetRevision">,
  storage = browserStorage()
): void {
  const store = readStore(storage);
  writeStore(
    {
      version: 1,
      drafts: store.drafts.filter((draft) => !matchesScope(draft, scope))
    },
    storage
  );
}

export function practiceDraftStorageKey(): string {
  return STORAGE_KEY;
}

function readStore(storage: Storage | null): PracticeDraftStore {
  if (storage === null) return emptyStore();
  const raw = storage.getItem(STORAGE_KEY);
  if (raw === null) return emptyStore();

  try {
    const parsed = JSON.parse(raw) as Partial<PracticeDraftStore>;
    if (parsed.version !== 1 || !Array.isArray(parsed.drafts)) return emptyStore();
    return {
      version: 1,
      drafts: parsed.drafts.map(normalizeDraft).slice(0, MAX_DRAFTS)
    };
  } catch {
    storage.removeItem(STORAGE_KEY);
    return emptyStore();
  }
}

function writeStore(store: PracticeDraftStore, storage: Storage | null): void {
  if (storage === null) return;
  storage.setItem(STORAGE_KEY, JSON.stringify(store));
}

function normalizeDraft(draft: PracticeDraft): PracticeDraft {
  const updatedAt = typeof draft.updatedAt === "string" && draft.updatedAt.length > 0 ? draft.updatedAt : nowIso();
  return {
    userId: String(draft.userId),
    problemId: String(draft.problemId),
    assetRevision: String(draft.assetRevision),
    clientAttemptId: String(draft.clientAttemptId),
    files: Array.isArray(draft.files) ? draft.files.map((file) => normalizeFile(file, updatedAt)) : [],
    updatedAt
  };
}

function normalizeFile(file: PracticeDraftFile, fallbackUpdatedAt: string): PracticeDraftFile {
  return {
    path: String(file.path),
    content: String(file.content),
    updatedAt: typeof file.updatedAt === "string" && file.updatedAt.length > 0 ? file.updatedAt : fallbackUpdatedAt
  };
}

function withFileTimestamps(files: PracticeAttemptFileRequest[], updatedAt: string): PracticeDraftFile[] {
  return files.map((file) => ({
    path: file.path,
    content: file.content,
    updatedAt
  }));
}

function matchesScope(
  draft: Pick<PracticeDraft, "userId" | "problemId" | "assetRevision">,
  scope: Pick<PracticeDraft, "userId" | "problemId" | "assetRevision">
): boolean {
  return draft.userId === scope.userId && draft.problemId === scope.problemId && draft.assetRevision === scope.assetRevision;
}

function createClientAttemptId(): string {
  if (globalThis.crypto?.randomUUID !== undefined) {
    return `attempt-${globalThis.crypto.randomUUID()}`;
  }
  return `attempt-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function browserStorage(): Storage | null {
  if (typeof window === "undefined") return null;
  return window.localStorage;
}

function emptyStore(): PracticeDraftStore {
  return { version: 1, drafts: [] };
}

function nowIso(): string {
  return new Date().toISOString();
}
