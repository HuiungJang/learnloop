export type HintRevealScope = {
  userId: string;
  problemId: string;
};

type HintRevealEntry = HintRevealScope & {
  hintIds: string[];
  updatedAt: string;
};

type HintRevealStore = {
  version: 1;
  entries: HintRevealEntry[];
};

type HintRevealLogEntry = HintRevealScope & {
  hintId: string;
  revealOrder: number;
  revealPolicy: string;
  revealedAt: string;
};

type HintRevealLogStore = {
  version: 1;
  entries: HintRevealLogEntry[];
};

const REVEAL_STORAGE_KEY = "learnloop:hint-reveals:v1";
const REVEAL_LOG_STORAGE_KEY = "learnloop:hint-reveal-log:v1";
const MAX_ENTRIES = 100;

export function loadRevealedHintIds(scope: HintRevealScope, storage = browserStorage()): string[] {
  return readRevealStore(storage).entries.find((entry) => sameScope(entry, scope))?.hintIds ?? [];
}

export function saveRevealedHintId(scope: HintRevealScope, hintId: string, storage = browserStorage()): string[] {
  const current = loadRevealedHintIds(scope, storage);
  const hintIds = current.includes(hintId) ? current : [...current, hintId];
  const now = new Date().toISOString();
  const store = readRevealStore(storage);
  const entries = [{ ...scope, hintIds, updatedAt: now }, ...store.entries.filter((entry) => !sameScope(entry, scope))].slice(0, MAX_ENTRIES);
  writeRevealStore({ version: 1, entries }, storage);
  return hintIds;
}

export function logHintReveal(
  scope: HintRevealScope,
  hint: { id: string; revealOrder: number; revealPolicy: string },
  storage = browserStorage()
): void {
  if (storage === null) return;
  const store = readRevealLogStore(storage);
  const entry: HintRevealLogEntry = {
    ...scope,
    hintId: hint.id,
    revealOrder: hint.revealOrder,
    revealPolicy: hint.revealPolicy,
    revealedAt: new Date().toISOString()
  };
  writeRevealLogStore({ version: 1, entries: [entry, ...store.entries].slice(0, MAX_ENTRIES) }, storage);
}

export function hintRevealStorageKey(): string {
  return REVEAL_STORAGE_KEY;
}

export function hintRevealLogStorageKey(): string {
  return REVEAL_LOG_STORAGE_KEY;
}

function readRevealStore(storage: Storage | null): HintRevealStore {
  if (storage === null) return { version: 1, entries: [] };
  const raw = storage.getItem(REVEAL_STORAGE_KEY);
  if (raw === null) return { version: 1, entries: [] };
  try {
    const parsed = JSON.parse(raw) as Partial<HintRevealStore>;
    if (parsed.version !== 1 || !Array.isArray(parsed.entries)) return { version: 1, entries: [] };
    return { version: 1, entries: parsed.entries.map(normalizeRevealEntry).slice(0, MAX_ENTRIES) };
  } catch {
    storage.removeItem(REVEAL_STORAGE_KEY);
    return { version: 1, entries: [] };
  }
}

function readRevealLogStore(storage: Storage | null): HintRevealLogStore {
  if (storage === null) return { version: 1, entries: [] };
  const raw = storage.getItem(REVEAL_LOG_STORAGE_KEY);
  if (raw === null) return { version: 1, entries: [] };
  try {
    const parsed = JSON.parse(raw) as Partial<HintRevealLogStore>;
    if (parsed.version !== 1 || !Array.isArray(parsed.entries)) return { version: 1, entries: [] };
    return { version: 1, entries: parsed.entries.map(normalizeRevealLogEntry).slice(0, MAX_ENTRIES) };
  } catch {
    storage.removeItem(REVEAL_LOG_STORAGE_KEY);
    return { version: 1, entries: [] };
  }
}

function writeRevealStore(store: HintRevealStore, storage: Storage | null): void {
  storage?.setItem(REVEAL_STORAGE_KEY, JSON.stringify(store));
}

function writeRevealLogStore(store: HintRevealLogStore, storage: Storage | null): void {
  storage?.setItem(REVEAL_LOG_STORAGE_KEY, JSON.stringify(store));
}

function normalizeRevealEntry(entry: HintRevealEntry): HintRevealEntry {
  return {
    userId: String(entry.userId),
    problemId: String(entry.problemId),
    hintIds: Array.isArray(entry.hintIds) ? entry.hintIds.map(String) : [],
    updatedAt: typeof entry.updatedAt === "string" ? entry.updatedAt : new Date().toISOString()
  };
}

function normalizeRevealLogEntry(entry: HintRevealLogEntry): HintRevealLogEntry {
  return {
    userId: String(entry.userId),
    problemId: String(entry.problemId),
    hintId: String(entry.hintId),
    revealOrder: Number.isFinite(entry.revealOrder) ? entry.revealOrder : 0,
    revealPolicy: String(entry.revealPolicy),
    revealedAt: typeof entry.revealedAt === "string" ? entry.revealedAt : new Date().toISOString()
  };
}

function sameScope(a: HintRevealScope, b: HintRevealScope): boolean {
  return a.userId === b.userId && a.problemId === b.problemId;
}

function browserStorage(): Storage | null {
  if (typeof window === "undefined") return null;
  return window.localStorage;
}
