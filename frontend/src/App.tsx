import {
  BookOpen,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Cloud,
  Database,
  Eye,
  FolderGit2,
  FolderOpen,
  GitPullRequest,
  KeyRound,
  Library,
  LayoutDashboard,
  ListChecks,
  LockKeyhole,
  LogIn,
  LogOut,
  Moon,
  Play,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  Sun,
  Trash2,
  UploadCloud,
  X,
  type LucideIcon
} from "lucide-react";
import { Suspense, lazy, type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import {
  ApiRequestError,
  createProvider,
  deleteEvidence,
  generateFromEvidence,
  getConversionTraces,
  createSession,
  fetchHealth,
  getEvidenceDetail,
  getEvidenceRetentionSettings,
  getLibrary,
  getPracticeProblem,
  getProgress,
  getRecommendations,
  installRunnerLanguage,
  listEvidence,
  listLocalRepositories,
  listRunnerLanguages,
  type HealthResponse,
  isConflictError,
  listProviders,
  type PracticeAttemptFileRequest,
  purgeEvidenceRaw,
  purgeEvidenceRawScope,
  refreshRunnerLanguages,
  removeRunnerLanguage,
  runPracticeAttempt,
  runLearningDemo,
  submitPracticeAttempt,
  syncLocalPracticeAttempt,
  type EvidenceDetailResponse,
  type EvidenceItemResponse,
  type EvidenceListResponse,
  type EvidenceRetentionMode,
  type EvidenceRetentionSettingsResponse,
  type ConversionTraceResponse,
  type Membership,
  type PatternCardResponse,
  type PracticeHintResponse,
  type PracticeProblemResponse,
  type PracticeRunResultResponse,
  type ProficiencyResponse,
  type ProviderResponse,
  type RunnerLanguageResponse,
  type SessionResponse,
  type SourceBundleSummaryResponse,
  updateEvidenceAttribution,
  updateEvidenceRetentionSettings,
  updateLocalRepository,
  type LocalRepositoryConsentResponse
} from "./api/client";
import { ensurePracticeDraft } from "./practice/practiceStorage";
import {
  enqueuePracticeSync,
  markPracticeConflict,
  markPracticeFailed,
  markPracticeSynced,
  markPracticeSyncing,
  type PracticeSyncStatus
} from "./practice/practiceSyncQueue";
import { loadRevealedHintIds, logHintReveal, saveRevealedHintId } from "./practice/hintProgress";

type LocalAiProvider = "codex" | "gemini" | "claude";
type LocalAuthMethod = "api_key" | "oauth";
type WorkspacePage = "dashboard" | "aiSetup" | "runners" | "collection" | "evidence" | "trace" | "practice";
type EditorTheme = "vs" | "vs-dark";
type HealthState =
  | { status: "pending" }
  | { status: "success"; data: HealthResponse }
  | { status: "error" };
type LibraryFilters = {
  language: string;
  tag: string;
  difficulty: string;
  publicationStatus: "published";
};
type PracticeSaveState = PracticeSyncStatus | "idle" | "running" | "submitted" | "submitting";
type PracticeSaveStatus = {
  state: PracticeSaveState;
  message: string;
};
type WorkbenchOverlay = "quick-open" | "command-palette" | null;
type AnswerDiffState = {
  language: string;
  path: string;
  referenceAnswer: string;
  submittedAnswer: string;
};

type LocalAiSettings = {
  provider: LocalAiProvider;
  authMethod: LocalAuthMethod;
  credentialLabel: string;
  providerConfigId?: string;
  model?: string;
  baseUrl?: string | null;
  apiKey?: string;
  configuredAt: string;
};
type LocalOAuthConnection = {
  status: "idle" | "starting" | "running" | "connected" | "failed" | "unavailable";
  message: string;
};
type CompanionOAuthResponse = {
  status: "idle" | "starting" | "running" | "connected" | "failed" | "missing";
  provider?: LocalAiProvider;
  credentialLabel?: string;
  message?: string;
};
type CompanionTokenResponse = {
  status?: string;
  token?: string;
  message?: string;
};
type CompanionDirectoryPickerResponse = {
  status?: "ok" | "cancelled" | "failed";
  path?: string | null;
  message?: string;
};
type RepositoryConsentStatus = "approved" | "revoked" | "always_ignored" | "missing";
type LocalRepositoryConsent = {
  repoIdentityHash: string;
  displayLabel: string;
  repoRoot: string | null;
  status: RepositoryConsentStatus;
  updatedAt: string;
};
type LocalWatcherState = "active" | "stopped" | "degraded" | "unavailable";
type LocalWatcherRegistration = {
  repoIdentityHash: string;
  repositoryDisplayLabel: string;
  repositoryStatus: RepositoryConsentStatus;
  state: LocalWatcherState;
  reason: string | null;
  lastReconciliationAt: string | null;
  lastReconciliationStatus: string | null;
  lastReconciliationChangedFileCount: number;
  lastReconciliationDiffCandidateCount: number;
  pendingChangeCount: number;
  settledChangeCount: number;
  droppedEventCount: number;
  reconciliationQueued: boolean;
};
type LocalWatcherCounts = Record<LocalWatcherState, number>;
type LocalUploadQueueStatus = {
  queued: number;
  discardedCount: number;
  nextAttemptAt: string | null;
};
type LocalWatcherStatus = {
  collectionEnabled: boolean;
  uploadQueue: LocalUploadQueueStatus;
  watcherCounts: LocalWatcherCounts;
  watchers: LocalWatcherRegistration[];
};
type LocalAdapterStatusValue = "idle" | "running" | "ok" | "failed" | "missing";
type LocalAdapterStatus = {
  provider: string;
  label: string;
  status: LocalAdapterStatusValue;
  reason: string | null;
  errorCode: string | null;
  lastEventType: string | null;
  lastEventAt: string | null;
};
type CompanionWatcherStatusResponse = {
  status?: string;
  collectionEnabled?: boolean;
  uploadQueue?: Partial<LocalUploadQueueStatus>;
  watcherCounts?: Partial<LocalWatcherCounts>;
  watchers?: Partial<LocalWatcherRegistration>[];
  message?: string;
};
type CompanionAdapterStatusResponse = {
  status?: string;
  adapters?: Partial<LocalAdapterStatus>[];
  message?: string;
};

const workflowCards = [
  {
    title: "Evidence Intake",
    description: "Collect Codex CLI and local-session evidence from approved repositories into one curation-ready bundle.",
    icon: UploadCloud,
    metric: "local inputs"
  },
  {
    title: "Pattern Generation",
    description: "Extract design patterns, APIs, libraries, algorithms, and configuration steps from approved evidence.",
    icon: Sparkles,
    metric: "AI assisted"
  },
  {
    title: "Local Curation",
    description: "Let the local owner keep, edit, delete, or generate practice from collected evidence.",
    icon: ListChecks,
    metric: "owner gate"
  },
  {
    title: "Practice Library",
    description: "Turn curated cards into implementation prompts, Q&A, and problem-solving exercises.",
    icon: Library,
    metric: "personal"
  }
];

const aiProviders: Array<{
  id: LocalAiProvider;
  label: string;
  backendProvider: "codex" | "gemini" | "claude";
  defaultModel: string;
  icon: LucideIcon;
  oauth: boolean;
}> = [
  { id: "codex", label: "Codex", backendProvider: "codex", defaultModel: "gpt-5.2", icon: Bot, oauth: true },
  { id: "gemini", label: "Gemini", backendProvider: "gemini", defaultModel: "gemini-2.5-flash", icon: Sparkles, oauth: true },
  { id: "claude", label: "Claude", backendProvider: "claude", defaultModel: "claude-sonnet-4-20250514", icon: Cloud, oauth: false }
];

const workspacePages: Array<{ id: WorkspacePage; label: string; icon: LucideIcon }> = [
  { id: "dashboard", label: "Overview", icon: LayoutDashboard },
  { id: "aiSetup", label: "AI setup", icon: KeyRound },
  { id: "runners", label: "Runners", icon: Play },
  { id: "collection", label: "Collection", icon: FolderGit2 },
  { id: "evidence", label: "Evidence", icon: Database },
  { id: "trace", label: "Trace", icon: GitPullRequest },
  { id: "practice", label: "Practice", icon: BookOpen }
];

const pageCopy: Record<WorkspacePage, { eyebrow: string; title: string; description: string }> = {
  dashboard: {
    eyebrow: "AI-assisted code learning workspace",
    title: "Generated code becomes curated practice.",
    description: "Run the local learning flow and review system health without mixing it with operational tools."
  },
  aiSetup: {
    eyebrow: "Local AI setup",
    title: "Choose your coding assistant.",
    description: "Connect the assistant used on this machine. Credentials stay local to this browser and companion."
  },
  runners: {
    eyebrow: "Local runner setup",
    title: "Install only the languages you use.",
    description: "TypeScript, Kotlin, and Java are default runner targets. Swift and Rust can be installed on demand."
  },
  collection: {
    eyebrow: "Local collection",
    title: "Control what local code can be collected.",
    description: "Approve repositories, monitor the watcher, and manage raw evidence retention in one operational screen."
  },
  evidence: {
    eyebrow: "Evidence curation",
    title: "Review collected source evidence.",
    description: "Inspect bounded excerpts, decide what can feed pattern generation, and purge or remove unsafe raw evidence."
  },
  trace: {
    eyebrow: "Learning pipeline",
    title: "Trace evidence into practice assets.",
    description: "Follow how collected evidence becomes patterns and exercises so gaps are easy to spot."
  },
  practice: {
    eyebrow: "Practice library",
    title: "Open generated exercises.",
    description: "Filter practice cards and continue work in the coding workbench only when a problem is open."
  }
};

const LOCAL_AI_STORAGE_PREFIX = "learnloop:local-ai:";
const LEGACY_LOCAL_AI_STORAGE_PREFIX = "ai-code-learning:local-ai:";
const SESSION_STORAGE_KEY = "learnloop:session";
const LOCAL_OWNER_USER_ID = "u-local-owner";
const EDITOR_THEME_STORAGE_KEY = "learnloop:editor-theme";
const RUNNER_ONBOARDING_STORAGE_PREFIX = "learnloop:runner-onboarding:";
const LOCAL_AI_COMPANION_URL = import.meta.env.VITE_LOCAL_AI_COMPANION_URL ?? "http://127.0.0.1:4317";
const PracticeEditorShell = lazy(() =>
  import("./practice/PracticeEditorShell").then((module) => ({ default: module.PracticeEditorShell }))
);
const PracticeAnswerDiff = lazy(() =>
  import("./practice/PracticeAnswerDiff").then((module) => ({ default: module.PracticeAnswerDiff }))
);

function localAiStorageKey(userId: string) {
  return `${LOCAL_AI_STORAGE_PREFIX}${userId}`;
}

function legacyLocalAiStorageKey(userId: string) {
  return `${LEGACY_LOCAL_AI_STORAGE_PREFIX}${userId}`;
}

function runnerOnboardingStorageKey(userId: string) {
  return `${RUNNER_ONBOARDING_STORAGE_PREFIX}${userId}`;
}

function readLocalAiSettings(userId: string): LocalAiSettings | null {
  const key = localAiStorageKey(userId);
  const legacyKey = legacyLocalAiStorageKey(userId);
  const raw = window.localStorage.getItem(key) ?? window.localStorage.getItem(legacyKey);
  if (raw === null) return null;

  try {
    const settings = sanitizeLocalAiSettings(JSON.parse(raw));
    if (settings === null) {
      window.localStorage.removeItem(key);
      window.localStorage.removeItem(legacyKey);
      return null;
    }
    if (window.localStorage.getItem(key) === null) {
      window.localStorage.setItem(key, JSON.stringify(settings));
      window.localStorage.removeItem(legacyKey);
    } else if (raw.includes("apiKey")) {
      window.localStorage.setItem(key, JSON.stringify(settings));
    }
    return settings;
  } catch {
    window.localStorage.removeItem(key);
    window.localStorage.removeItem(legacyKey);
    return null;
  }
}

function sanitizeLocalAiSettings(value: unknown): LocalAiSettings | null {
  if (value === null || typeof value !== "object") return null;
  const settings = value as Partial<LocalAiSettings>;
  const providerId = aiProviders.find((provider) => provider.id === settings.provider)?.id;
  if (providerId === undefined) return null;
  if (settings.authMethod !== "api_key" && settings.authMethod !== "oauth") return null;
  if (typeof settings.credentialLabel !== "string" || typeof settings.configuredAt !== "string") return null;
  if (settings.authMethod === "api_key" && (typeof settings.providerConfigId !== "string" || settings.providerConfigId.length === 0)) return null;

  return {
    provider: providerId,
    authMethod: settings.authMethod,
    credentialLabel: settings.credentialLabel,
    providerConfigId: settings.providerConfigId,
    model: settings.model,
    baseUrl: settings.baseUrl ?? null,
    configuredAt: settings.configuredAt
  };
}

function repositoryStatusCounts(repositories: LocalRepositoryConsent[]): Record<RepositoryConsentStatus, number> {
  return repositories.reduce(
    (counts, repository) => ({
      ...counts,
      [repository.status]: counts[repository.status] + 1
    }),
    { approved: 0, revoked: 0, always_ignored: 0, missing: 0 }
  );
}

function repositoryStatusLabel(status: RepositoryConsentStatus): string {
  if (status === "always_ignored") return "ignored";
  return status;
}

function watcherStateLabel(watcher: LocalWatcherRegistration): string {
  if (watcher.reason === "collection_disabled") return "disabled";
  if (watcher.reconciliationQueued) return "queued";
  return watcher.state;
}

function watcherStatusClass(watcher: LocalWatcherRegistration): string {
  if (watcher.state === "active") return "status-success";
  if (watcher.state === "degraded") return "status-warning";
  if (watcher.reason === "collection_disabled") return "status-neutral";
  return "status-danger";
}

function adapterStatusClass(adapter: LocalAdapterStatus): string {
  if (adapter.status === "ok") return "status-success";
  if (adapter.status === "running") return "status-warning";
  if (adapter.status === "idle") return "status-neutral";
  return "status-danger";
}

function runnerStatusClass(status: RunnerLanguageResponse["status"]): string {
  if (status === "installed") return "status-success";
  if (status === "installing" || status === "missing") return "status-warning";
  if (status === "failed") return "status-danger";
  return "status-neutral";
}

function runnerStatusLabel(language: RunnerLanguageResponse): string {
  if (language.status === "installed") return "Installed";
  if (language.status === "installing") return "Installing";
  if (language.status === "missing") return "Missing";
  if (language.status === "failed") return "Failed";
  return language.desiredEnabled ? "Selected" : "Available";
}

function runnerInstallStageLabel(stage: RunnerLanguageResponse["installStage"]): string {
  if (stage === "checking_image") return "Checking image";
  if (stage === "building_local_image") return "Building local image";
  if (stage === "pulling_image") return "Pulling image";
  if (stage === "verifying_image") return "Verifying image";
  return "";
}

function runnerImageSourceLabel(source: RunnerLanguageResponse["imageSource"]): string {
  if (source === "local") return "local image";
  if (source === "registry") return "registry image";
  return "bundled image";
}

function formatRunnerSize(sizeMb: number): string {
  if (sizeMb >= 1000) return `${(sizeMb / 1000).toFixed(1)}GB`;
  return `${sizeMb}MB`;
}

function replaceRunnerLanguage(
  languages: RunnerLanguageResponse[],
  updated: RunnerLanguageResponse,
): RunnerLanguageResponse[] {
  const next = languages.map((language) => (language.language === updated.language ? updated : language));
  return next.some((language) => language.language === updated.language) ? next : [...next, updated];
}

function primaryPracticeLanguage(problem: PracticeProblemResponse): string {
  return problem.files.find((file) => file.role === "starter")?.language ?? problem.files[0]?.language ?? "typescript";
}

function bundleCurationLabel(bundle: SourceBundleSummaryResponse): string {
  if (bundle.userAttribution === "use_for_generation") return "Use";
  if (isGuiCorrelatedEvidence(bundle) && bundle.userAttribution === "manual") return "Confirmed";
  if (bundle.userAttribution === "manual") return "Manual";
  if (bundle.status === "user_confirmation_required") return "Needs confirmation";
  if (bundle.status === "quarantined_secret") return "Quarantined";
  if (bundle.status === "purged_raw") return "Purged";
  return "Uncurated";
}

function bundleStatusClass(bundle: SourceBundleSummaryResponse): string {
  if (bundle.status === "quarantined_secret") return "status-danger";
  if (bundle.userAttribution === "use_for_generation") return "status-success";
  if (bundle.status === "user_confirmation_required") return "status-warning";
  return "status-neutral";
}

function attributionLabel(value: string): string {
  if (value === "ai_assisted") return "AI assisted";
  if (value === "gui_correlated") return "GUI correlated";
  if (value === "manual_or_unknown") return "Manual or unknown";
  if (value === "use_for_generation") return "Use for generation";
  if (value === "manual") return "Manual";
  if (value === "delete") return "Delete";
  if (value === "uncurated") return "Uncurated";
  return value.replaceAll("_", " ");
}

function reasonCodes(value: string): string[] {
  try {
    const parsed = JSON.parse(value) as unknown;
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

function isGuiCorrelatedEvidence(bundle: SourceBundleSummaryResponse): boolean {
  return bundle.autoAttribution === "gui_correlated" || bundle.status === "user_confirmation_required";
}

function reasonCodeLabel(value: string): string {
  if (value === "gui_activity_window") return "GUI activity window";
  if (value === "repo_changed") return "Repository changed";
  if (value === "single_ai_tool") return "Single AI tool";
  if (value === "competing_ai_tools") return "Competing AI tools";
  if (value === "tool_session") return "Tool session";
  if (value === "changed_files") return "Changed files";
  if (value === "human_review") return "Human review";
  if (value === "curation_approved") return "Curation approved";
  if (value === "user_deleted") return "User deleted";
  return value.replaceAll("_", " ");
}

function guiCorrelationExplanation(bundle: SourceBundleSummaryResponse): string {
  const reasons = reasonCodes(bundle.attributionReasonsJson);
  if (reasons.includes("competing_ai_tools")) {
    return "Lower confidence because more than one AI tool was active while repository files changed.";
  }
  return "Lower confidence because this was inferred from app activity and repository changes, not direct AI output or patch data.";
}

function generationBlockedReason(bundle: SourceBundleSummaryResponse): string | null {
  if (isGuiCorrelatedEvidence(bundle)) {
    return "Generation stays blocked until direct AI output or patch data is attached.";
  }
  if (bundle.sourceKind === "local_ai_session" && bundle.status !== "generation_eligible") {
    return "Generation is blocked until this evidence passes local safety checks.";
  }
  if (bundle.sourceKind === "local_ai_session" && bundle.userAttribution !== "use_for_generation") {
    return "Mark this evidence for generation before creating practice.";
  }
  return null;
}

function formatShortDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function formatBytes(value: number | null): string {
  if (value === null) return "size unknown";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function retentionSummary(settings: EvidenceRetentionSettingsResponse | null): string {
  if (settings === null) return "Policy not loaded";
  if (settings.retentionMode === "disabled") return "Automatic cleanup disabled";
  if (settings.retentionMode === "immediate") return "Raw evidence purges immediately";
  return `Raw evidence retained for ${settings.retentionDays ?? 30} days`;
}

function normalizeRetentionDays(value: string): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : 30;
}

function retentionDaysFormValue(settings: EvidenceRetentionSettingsResponse): string {
  return settings.retentionMode === "default" ? String(settings.retentionDays ?? 30) : "30";
}

function toLocalRepositoryConsent(repository: LocalRepositoryConsentResponse): LocalRepositoryConsent {
  return {
    repoIdentityHash: repository.repoIdentityHash,
    displayLabel: repository.displayLabel,
    repoRoot: typeof repository.repoRoot === "string" && repository.repoRoot.length > 0 ? repository.repoRoot : null,
    status: repository.status,
    updatedAt: repository.updatedAt
  };
}

function toLocalWatcherStatus(payload: CompanionWatcherStatusResponse): LocalWatcherStatus {
  const counts = payload.watcherCounts ?? {};
  const queue = payload.uploadQueue ?? {};
  return {
    collectionEnabled: payload.collectionEnabled !== false,
    uploadQueue: {
      queued: safeNumber(queue.queued),
      discardedCount: safeNumber(queue.discardedCount),
      nextAttemptAt: typeof queue.nextAttemptAt === "string" ? queue.nextAttemptAt : null
    },
    watcherCounts: {
      active: safeNumber(counts.active),
      stopped: safeNumber(counts.stopped),
      degraded: safeNumber(counts.degraded),
      unavailable: safeNumber(counts.unavailable)
    },
    watchers: Array.isArray(payload.watchers) ? payload.watchers.map(toLocalWatcherRegistration) : []
  };
}

function toLocalWatcherRegistration(value: Partial<LocalWatcherRegistration>): LocalWatcherRegistration {
  const state = isLocalWatcherState(value.state) ? value.state : "unavailable";
  return {
    repoIdentityHash: typeof value.repoIdentityHash === "string" ? value.repoIdentityHash : "",
    repositoryDisplayLabel: typeof value.repositoryDisplayLabel === "string" ? value.repositoryDisplayLabel : "repository",
    repositoryStatus: isRepositoryConsentStatus(value.repositoryStatus) ? value.repositoryStatus : "missing",
    state,
    reason: typeof value.reason === "string" ? value.reason : null,
    lastReconciliationAt: typeof value.lastReconciliationAt === "string" ? value.lastReconciliationAt : null,
    lastReconciliationStatus: typeof value.lastReconciliationStatus === "string" ? value.lastReconciliationStatus : null,
    lastReconciliationChangedFileCount: safeNumber(value.lastReconciliationChangedFileCount),
    lastReconciliationDiffCandidateCount: safeNumber(value.lastReconciliationDiffCandidateCount),
    pendingChangeCount: safeNumber(value.pendingChangeCount),
    settledChangeCount: safeNumber(value.settledChangeCount),
    droppedEventCount: safeNumber(value.droppedEventCount),
    reconciliationQueued: value.reconciliationQueued === true
  };
}

function toLocalAdapterStatuses(payload: CompanionAdapterStatusResponse): LocalAdapterStatus[] {
  return Array.isArray(payload.adapters) ? payload.adapters.map(toLocalAdapterStatus) : [];
}

function toLocalAdapterStatus(value: Partial<LocalAdapterStatus>): LocalAdapterStatus {
  const status = isLocalAdapterStatusValue(value.status) ? value.status : "idle";
  return {
    provider: typeof value.provider === "string" ? value.provider : "unknown",
    label: typeof value.label === "string" ? value.label : "Unknown adapter",
    status,
    reason: typeof value.reason === "string" ? value.reason : null,
    errorCode: typeof value.errorCode === "string" ? value.errorCode : null,
    lastEventType: typeof value.lastEventType === "string" ? value.lastEventType : null,
    lastEventAt: typeof value.lastEventAt === "string" ? value.lastEventAt : null
  };
}

function isLocalAdapterStatusValue(value: unknown): value is LocalAdapterStatusValue {
  return value === "idle" || value === "running" || value === "ok" || value === "failed" || value === "missing";
}

function isLocalWatcherState(value: unknown): value is LocalWatcherState {
  return value === "active" || value === "stopped" || value === "degraded" || value === "unavailable";
}

function isRepositoryConsentStatus(value: unknown): value is RepositoryConsentStatus {
  return value === "approved" || value === "revoked" || value === "always_ignored" || value === "missing";
}

function safeNumber(value: unknown): number {
  return Number.isFinite(value) ? Number(value) : 0;
}

function repositoryDisplayLabel(value: string): string {
  const normalized = value.trim();
  const lastSegment = normalized.split(/[\\/]/).filter(Boolean).at(-1);
  return (lastSegment ?? normalized).slice(0, 80);
}

async function repositoryIdentityHash(value: string): Promise<string> {
  const data = new TextEncoder().encode(value.trim());
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function readEditorTheme(): EditorTheme {
  return window.localStorage.getItem(EDITOR_THEME_STORAGE_KEY) === "vs" ? "vs" : "vs-dark";
}

function readStoredSession(): SessionResponse | null {
  const raw = window.sessionStorage.getItem(SESSION_STORAGE_KEY);
  if (raw === null) return null;

  try {
    const nextSession = JSON.parse(raw) as Partial<SessionResponse>;
    if (
      typeof nextSession.token !== "string" ||
      typeof nextSession.expiresAt !== "string" ||
      typeof nextSession.user?.id !== "string" ||
      typeof nextSession.user.email !== "string" ||
      typeof nextSession.user.displayName !== "string" ||
      !Array.isArray(nextSession.user.memberships)
    ) {
      throw new Error("Invalid stored session");
    }
    const session = nextSession as SessionResponse;
    if (!isLocalOwnerSession(session)) {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    return null;
  }
}

function storeSession(nextSession: SessionResponse | null) {
  if (nextSession === null) {
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY);
    return;
  }
  window.sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(nextSession));
}

function defaultOauthLabel(provider: LocalAiProvider) {
  return provider === "codex" ? "Codex CLI OAuth" : "Google OAuth";
}

function oauthCommand(provider: LocalAiProvider) {
  return provider === "codex" ? "codex login" : "gcloud auth application-default login";
}

function oauthStatusLabel(status: LocalOAuthConnection["status"]) {
  if (status === "connected") return "Connected";
  if (status === "starting" || status === "running") return "Connecting";
  if (status === "failed" || status === "unavailable") return "Needs attention";
  return "Not connected";
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

async function readCompanionResponse(response: Response): Promise<CompanionOAuthResponse> {
  const payload = (await response.json().catch(() => ({}))) as Partial<CompanionOAuthResponse>;
  return {
    status: payload.status ?? (response.ok ? "idle" : "failed"),
    provider: payload.provider,
    credentialLabel: payload.credentialLabel,
    message: payload.message
  };
}

async function readLocalCompanionToken(): Promise<string> {
  const response = await fetch(`${LOCAL_AI_COMPANION_URL}/auth/token`);
  const payload = (await response.json().catch(() => ({}))) as CompanionTokenResponse;
  if (!response.ok || typeof payload.token !== "string" || payload.token.length === 0) {
    throw new Error(payload.message || "Local companion token is unavailable.");
  }
  return payload.token;
}

function primaryMembership(session: SessionResponse | null): Membership | null {
  return session?.user.memberships[0] ?? null;
}

function isLocalOwnerSession(session: SessionResponse): boolean {
  return session.user.id === LOCAL_OWNER_USER_ID;
}

export function App() {
  const editorSnapshotRef = useRef<(() => PracticeAttemptFileRequest[]) | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [session, setSession] = useState<SessionResponse | null>(() => readStoredSession());
  const [providers, setProviders] = useState<ProviderResponse[]>([]);
  const [latestCard, setLatestCard] = useState<PatternCardResponse | null>(null);
  const [libraryCards, setLibraryCards] = useState<PatternCardResponse[]>([]);
  const [evidenceList, setEvidenceList] = useState<EvidenceListResponse>({ bundles: [], page: 0, pageSize: 50, total: 0 });
  const [evidenceLoading, setEvidenceLoading] = useState(false);
  const [evidenceError, setEvidenceError] = useState("");
  const [selectedEvidenceId, setSelectedEvidenceId] = useState<string | null>(null);
  const [selectedEvidenceDetail, setSelectedEvidenceDetail] = useState<EvidenceDetailResponse | null>(null);
  const [evidenceDetailLoading, setEvidenceDetailLoading] = useState(false);
  const [evidenceActionMessage, setEvidenceActionMessage] = useState("");
  const [progressScores, setProgressScores] = useState<ProficiencyResponse[]>([]);
  const [recommendationCards, setRecommendationCards] = useState<PatternCardResponse[]>([]);
  const [conversionTraces, setConversionTraces] = useState<ConversionTraceResponse[]>([]);
  const [conversionTraceError, setConversionTraceError] = useState("");
  const [activePractice, setActivePractice] = useState<PracticeProblemResponse | null>(null);
  const [activePracticePath, setActivePracticePath] = useState<string | null>(null);
  const [practiceSaveStatus, setPracticeSaveStatus] = useState<PracticeSaveStatus>({ state: "idle", message: "Not saved" });
  const [revealedHintIds, setRevealedHintIds] = useState<string[]>([]);
  const [provenanceExpanded, setProvenanceExpanded] = useState(true);
  const [workbenchOverlay, setWorkbenchOverlay] = useState<WorkbenchOverlay>(null);
  const [workbenchQuery, setWorkbenchQuery] = useState("");
  const [diffVisible, setDiffVisible] = useState(false);
  const [answerDiff, setAnswerDiff] = useState<AnswerDiffState | null>(null);
  const [practiceLoading, setPracticeLoading] = useState(false);
  const [practiceError, setPracticeError] = useState("");
  const [libraryFilters, setLibraryFilters] = useState<LibraryFilters>({
    language: "",
    tag: "",
    difficulty: "",
    publicationStatus: "published"
  });
  const [libraryLoading, setLibraryLoading] = useState(false);
  const [libraryError, setLibraryError] = useState("");
  const [activity, setActivity] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [localAiSettings, setLocalAiSettings] = useState<LocalAiSettings | null>(null);
  const [runnerLanguages, setRunnerLanguages] = useState<RunnerLanguageResponse[]>([]);
  const [runnerLoading, setRunnerLoading] = useState(false);
  const [runnerPendingLanguages, setRunnerPendingLanguages] = useState<Set<string>>(() => new Set());
  const [runnerMessage, setRunnerMessage] = useState("");
  const [localRepositories, setLocalRepositories] = useState<LocalRepositoryConsent[]>([]);
  const [localWatcherStatus, setLocalWatcherStatus] = useState<LocalWatcherStatus | null>(null);
  const [localAdapterStatuses, setLocalAdapterStatuses] = useState<LocalAdapterStatus[]>([]);
  const [localWatcherLoading, setLocalWatcherLoading] = useState(false);
  const [localWatcherError, setLocalWatcherError] = useState("");
  const [retentionSettings, setRetentionSettings] = useState<EvidenceRetentionSettingsResponse | null>(null);
  const [retentionMode, setRetentionMode] = useState<EvidenceRetentionMode>("default");
  const [retentionDays, setRetentionDays] = useState("30");
  const [retentionLoading, setRetentionLoading] = useState(false);
  const [retentionMessage, setRetentionMessage] = useState("");
  const [repositoryLabel, setRepositoryLabel] = useState("");
  const [repositoryPickerLoading, setRepositoryPickerLoading] = useState(false);
  const [activePage, setActivePage] = useState<WorkspacePage>("dashboard");
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);
  const [selectedPracticeCardId, setSelectedPracticeCardId] = useState<string | null>(null);
  const [selectedAiProvider, setSelectedAiProvider] = useState<LocalAiProvider>("codex");
  const [selectedAuthMethod, setSelectedAuthMethod] = useState<LocalAuthMethod>("api_key");
  const [editorTheme, setEditorTheme] = useState<EditorTheme>(() => readEditorTheme());
  const [localApiKey, setLocalApiKey] = useState("");
  const [localProviderModel, setLocalProviderModel] = useState(aiProviders[0].defaultModel);
  const [localProviderBaseUrl, setLocalProviderBaseUrl] = useState("");
  const [onboardingSaving, setOnboardingSaving] = useState(false);
  const [selectedGenerationProviderId, setSelectedGenerationProviderId] = useState("provider-local-mock");
  const [oauthLabel, setOauthLabel] = useState("");
  const [oauthConnection, setOauthConnection] = useState<LocalOAuthConnection>({ status: "idle", message: "" });
  const [onboardingError, setOnboardingError] = useState("");
  const [health, setHealth] = useState<HealthState>({ status: "pending" });
  const sessionRef = useRef(session);
  const restoredSessionRef = useRef(session !== null);
  const evidenceDetailRequestRef = useRef(0);

  const membership = useMemo(() => primaryMembership(session), [session]);
  const selectedProvider = aiProviders.find((provider) => provider.id === selectedAiProvider) ?? aiProviders[0];
  const generationProviders = useMemo(() => providers.filter((provider) => provider.status === "active"), [providers]);
  const selectedGenerationProvider = generationProviders.find((provider) => provider.id === selectedGenerationProviderId) ?? null;
  const activePageContent = pageCopy[activePage];

  const healthLabel =
    health.status === "success"
      ? `API ${health.data.status}`
      : health.status === "pending"
        ? "API checking"
        : "API offline";

  useEffect(() => {
    let ignore = false;

    fetchHealth()
      .then((data) => {
        if (!ignore) setHealth({ status: "success", data });
      })
      .catch(() => {
        if (!ignore) setHealth({ status: "error" });
      });

    return () => {
      ignore = true;
    };
  }, []);

  useEffect(() => {
    if (!selectedProvider.oauth && selectedAuthMethod === "oauth") {
      setSelectedAuthMethod("api_key");
    }
  }, [selectedAuthMethod, selectedProvider.oauth]);

  useEffect(() => {
    setLocalProviderModel(selectedProvider.defaultModel);
    setLocalProviderBaseUrl("");
  }, [selectedProvider.defaultModel]);

  useEffect(() => {
    if (generationProviders.length === 0) {
      setSelectedGenerationProviderId("");
      return;
    }
    if (!generationProviders.some((provider) => provider.id === selectedGenerationProviderId)) {
      setSelectedGenerationProviderId(generationProviders[0].id);
    }
  }, [generationProviders, selectedGenerationProviderId]);

  useEffect(() => {
    setOauthConnection({ status: "idle", message: "" });
  }, [selectedAiProvider, selectedAuthMethod]);

  useEffect(() => {
    sessionRef.current = session;
    storeSession(session);
    if (session === null) {
      setActivePage("dashboard");
      setLocalRepositories([]);
      setLocalWatcherStatus(null);
      setLocalAdapterStatuses([]);
      setRunnerLanguages([]);
      setRunnerPendingLanguages(new Set());
      setRunnerMessage("");
      setRetentionSettings(null);
      setRetentionMessage("");
      setLocalWatcherError("");
    } else {
      void refreshLocalRepositories(session);
      void loadRunnerLanguages(session);
    }
  }, [session]);

  useEffect(() => {
    if (session === null || activePage !== "collection") return;
    void refreshLocalWatcherStatus();
  }, [activePage, session]);

  useEffect(() => {
    if (session === null || activePage !== "runners") return;
    void loadRunnerLanguages(session, true);
  }, [activePage, session]);

  useEffect(() => {
    if (session === null || activePage !== "runners") return;
    if (!runnerLanguages.some((language) => language.status === "installing")) return;
    const intervalId = window.setInterval(() => {
      void pollRunnerLanguages(session);
    }, 2000);
    return () => window.clearInterval(intervalId);
  }, [activePage, runnerLanguages, session]);

  useEffect(() => {
    if (!restoredSessionRef.current || session === null) return;
    restoredSessionRef.current = false;

    const storedSettings = readLocalAiSettings(session.user.id);
    setLocalAiSettings(storedSettings);
    if (storedSettings === null) {
      openLocalAiSetup();
    } else {
      setActivePage("dashboard");
    }
    refreshProviders(session).catch(() => logout());
  }, [session]);

  useEffect(() => {
    if (session === null || membership === null || activePage !== "practice") return;
    void refreshLibrary(session);
  }, [activePage, libraryFilters, membership, session]);

  useEffect(() => {
    if (session === null || membership === null) return;
    if (activePage === "dashboard" || activePage === "practice") {
      void refreshLearningSignals(session);
    }
    if (activePage === "trace") {
      void refreshConversionTraces(session);
    }
    if (activePage === "evidence") {
      void refreshEvidence(session);
    }
  }, [activePage, membership, session]);

  if (session === null) {
    return (
      <main className="auth-shell">
        <section className="auth-panel">
          <a className="brand" href="/">
            <span className="brand-mark">LL</span>
            <span>LearnLoop</span>
          </a>

          <div>
            <p className="eyebrow">Local learning workspace</p>
            <h1>Open your local learning workspace.</h1>
          </div>

          <form className="auth-form" onSubmit={handleAuthSubmit}>
            <label>
              <span>Email</span>
              <input autoComplete="email" onChange={(event) => setEmail(event.target.value)} required type="email" value={email} />
            </label>
            <label>
              <span>Password</span>
              <input
                autoComplete="current-password"
                onChange={(event) => setPassword(event.target.value)}
                required
                type="password"
                value={password}
              />
            </label>
            {authError.length > 0 ? <p className="form-error">{authError}</p> : null}
            <button className="primary-button" type="submit">
              <LogIn aria-hidden="true" size={16} />
              Login
            </button>
          </form>
        </section>

        <section className="auth-preview" aria-label="Local learning workflow">
          {workflowCards.map((card) => {
            const Icon = card.icon;
            return (
              <article className="workflow-card" key={card.title}>
                <div className="card-heading">
                  <span className="icon-pill">
                    <Icon aria-hidden="true" size={18} />
                  </span>
                  <span>{card.metric}</span>
                </div>
                <h2>{card.title}</h2>
                <p>{card.description}</p>
              </article>
            );
          })}
        </section>
      </main>
    );
  }

  return (
    <div className="app-shell">
      <aside className="sidebar" aria-label="Primary">
        <button className="brand brand-button" onClick={goToDashboard} type="button">
          <span className="brand-mark">LL</span>
          <span>LearnLoop</span>
        </button>

        <div className="account-card">
          <strong>{session.user.displayName}</strong>
          <span>{session.user.email}</span>
          <small>{membership?.role === "admin" ? "Local owner" : membership?.role ?? "member"}</small>
        </div>

        <nav className="nav-list" aria-label="Workspace pages">
          {workspacePages.map((page) => {
            const Icon = page.icon;
            const status =
              page.id === "aiSetup"
                ? localAiSettings === null
                  ? "Required"
                  : `${localAiSettings.provider} · ${localAiSettings.authMethod.replace("_", " ")}`
                : pageCopy[page.id].eyebrow;
            return (
              <button className={activePage === page.id ? "nav-active" : ""} key={page.id} onClick={() => setActivePage(page.id)} type="button">
                <Icon aria-hidden="true" size={16} />
                <span>
                  <strong>{page.label}</strong>
                  <small>{status}</small>
                </span>
              </button>
            );
          })}
        </nav>

        <button className="secondary-button sidebar-action" onClick={logout} type="button">
          <LogOut aria-hidden="true" size={16} />
          Logout
        </button>

        <div className="sidebar-status">
          <ShieldCheck aria-hidden="true" size={18} />
          <span>Local personal mode</span>
        </div>
      </aside>

      <main className="workspace">
        {renderPageHeader()}
        {renderActivePage()}
      </main>
    </div>
  );

  function renderPageHeader() {
    return (
      <header className="topbar">
        <div>
          <p className="eyebrow">{activePageContent.eyebrow}</p>
          <h1>{activePageContent.title}</h1>
          <p className="page-description">{activePageContent.description}</p>
        </div>
        <div className={`health-pill ${health.status}`}>
          <CheckCircle2 aria-hidden="true" size={16} />
          <span>{healthLabel}</span>
        </div>
      </header>
    );
  }

  function renderActivePage() {
    if (activePage === "aiSetup") return renderAiSetupPage();
    if (activePage === "runners") return renderRunnerPage();
    if (activePage === "collection") return renderCollectionPage();
    if (activePage === "evidence") return renderEvidencePage();
    if (activePage === "trace") return renderTracePage();
    if (activePage === "practice") return renderPracticePage();
    return renderDashboardPage();
  }

  function renderDashboardPage() {
    if (session === null) return null;
    const currentSession = session;

    return (
      <>
        <section className="summary-grid" aria-label="Local learning workflow">
          {workflowCards.map((card) => {
            const Icon = card.icon;

            return (
              <article className="workflow-card" key={card.title}>
                <div className="card-heading">
                  <span className="icon-pill">
                    <Icon aria-hidden="true" size={18} />
                  </span>
                  <span>{card.metric}</span>
                </div>
                <h2>{card.title}</h2>
                <p>{card.description}</p>
              </article>
            );
          })}
        </section>

        <section className="dashboard-grid">
          <div className="panel panel-wide">
            <div className="panel-title">
              <GitPullRequest aria-hidden="true" size={20} />
              <h2>Workflow Run</h2>
            </div>
            <div className="action-row">
              <button className="secondary-button" onClick={() => refreshProviders(currentSession)} type="button">
                <RefreshCw aria-hidden="true" size={16} />
                {currentSession.user.displayName}
              </button>
              <button className="primary-button" disabled={isRunning} onClick={runDemo} type="button">
                <Play aria-hidden="true" size={16} />
                {isRunning ? "Running" : "Run flow"}
              </button>
            </div>
            <div className="status-list">
              {activity.map((item) => (
                <span key={item}>{item}</span>
              ))}
              {activity.length === 0 ? <span>Ready</span> : null}
            </div>
          </div>

          <div className="panel">
            <div className="panel-title">
              <ShieldCheck aria-hidden="true" size={20} />
              <h2>Providers</h2>
            </div>
            <div className="review-table">
              {providers.map((provider) => (
                <div className="review-row" key={provider.id}>
                  <span>{provider.provider} / {provider.model}</span>
                  <small>{provider.scope}</small>
                  <strong>{provider.status}</strong>
                </div>
              ))}
            </div>
          </div>

          <div className="panel">
            <div className="panel-title">
              <Library aria-hidden="true" size={20} />
              <h2>Progress</h2>
            </div>
            <div className="review-table">
              {progressScores.length === 0 ? (
                <p className="muted-copy">No submissions yet.</p>
              ) : (
                progressScores.slice(0, 6).map((score) => (
                  <div className="review-row" key={score.tagName}>
                    <span>{score.tagName}</span>
                    <strong>{score.score}</strong>
                  </div>
                ))
              )}
            </div>
          </div>
        </section>
      </>
    );
  }

  function renderAiSetupPage() {
    return (
      <section className="onboarding-panel ai-setup-page">
        <form className="onboarding-form" onSubmit={saveLocalAiSettings}>
          <div className="provider-grid" role="radiogroup" aria-label="AI provider">
            {aiProviders.map((provider) => {
              const Icon = provider.icon;
              return (
                <button
                  aria-checked={selectedAiProvider === provider.id}
                  className={selectedAiProvider === provider.id ? "provider-selected" : ""}
                  key={provider.id}
                  onClick={() => setSelectedAiProvider(provider.id)}
                  role="radio"
                  type="button"
                >
                  <Icon aria-hidden="true" size={18} />
                  <span>{provider.label}</span>
                </button>
              );
            })}
          </div>

          <div className="auth-method-row">
            <button className={selectedAuthMethod === "api_key" ? "nav-active" : ""} onClick={() => setSelectedAuthMethod("api_key")} type="button">
              <LockKeyhole aria-hidden="true" size={16} />
              API key
            </button>
            <button
              className={selectedAuthMethod === "oauth" ? "nav-active" : ""}
              disabled={!selectedProvider.oauth}
              onClick={() => setSelectedAuthMethod("oauth")}
              type="button"
            >
              <Cloud aria-hidden="true" size={16} />
              OAuth
            </button>
          </div>

          {selectedAuthMethod === "api_key" ? (
            <>
              <label>
                <span>Model</span>
                <input autoComplete="off" onChange={(event) => setLocalProviderModel(event.target.value)} type="text" value={localProviderModel} />
              </label>
              <label>
                <span>Base URL</span>
                <input
                  autoComplete="off"
                  onChange={(event) => setLocalProviderBaseUrl(event.target.value)}
                  placeholder="Default provider endpoint"
                  type="url"
                  value={localProviderBaseUrl}
                />
              </label>
              <label>
                <span>API key</span>
                <input autoComplete="off" onChange={(event) => setLocalApiKey(event.target.value)} type="password" value={localApiKey} />
              </label>
            </>
          ) : (
            <div className="oauth-setup-stack">
              <div className="oauth-connect-row">
                <button
                  className="secondary-button"
                  disabled={oauthConnection.status === "starting" || oauthConnection.status === "running"}
                  onClick={startOAuthConnection}
                  type="button"
                >
                  <Cloud aria-hidden="true" size={16} />
                  {oauthConnection.status === "starting" || oauthConnection.status === "running" ? "Connecting" : `Connect ${selectedProvider.label}`}
                </button>
                <span aria-live="polite" className={`oauth-status oauth-status-${oauthConnection.status}`}>
                  {oauthStatusLabel(oauthConnection.status)}
                </span>
              </div>
              <label>
                <span>Local OAuth profile</span>
                <input
                  autoComplete="off"
                  onChange={(event) => setOauthLabel(event.target.value)}
                  placeholder={selectedAiProvider === "codex" ? "Codex CLI OAuth" : "Google OAuth"}
                  type="text"
                  value={oauthLabel}
                />
              </label>
              {oauthConnection.message.length > 0 ? <p className="oauth-message">{oauthConnection.message}</p> : null}
            </div>
          )}

          <div className="local-only-note">
            <ShieldCheck aria-hidden="true" size={16} />
            <span>{selectedAuthMethod === "api_key" ? "Encrypted in this local backend." : "Stored only in this browser."}</span>
          </div>

          {selectedAuthMethod === "oauth" ? (
            <div className="oauth-command">
              <span>{oauthCommand(selectedAiProvider)}</span>
            </div>
          ) : null}

          {onboardingError.length > 0 ? <p className="form-error">{onboardingError}</p> : null}
          <button className="primary-button" disabled={onboardingSaving} type="submit">
            <CheckCircle2 aria-hidden="true" size={16} />
            {onboardingSaving ? "Saving" : "Save local setup"}
          </button>
        </form>
      </section>
    );
  }

  function renderRunnerPage() {
    return (
      <section className="page-stack">
        <div className="panel panel-wide">
          <div className="panel-title">
            <Play aria-hidden="true" size={20} />
            <h2>Runner Languages</h2>
          </div>
          <div className="action-row">
            <button className="secondary-button" disabled={runnerLoading || session === null} onClick={() => session !== null && void loadRunnerLanguages(session, true)} type="button">
              <RefreshCw aria-hidden="true" size={16} />
              {runnerLoading ? "Checking" : "Refresh"}
            </button>
            <span className="pagination-status">{runnerLanguages.filter((language) => language.installed).length} installed</span>
          </div>
          {runnerMessage.length > 0 ? <p className="action-message">{runnerMessage}</p> : null}
          <div className="review-table runner-language-list">
            {runnerLanguages.map((language) => {
              const stageLabel = runnerInstallStageLabel(language.installStage);
              const rowPending = runnerPendingLanguages.has(language.language) || language.status === "installing";
              return (
                <div className="review-row runner-language-row" key={language.language}>
                  <div>
                    <span>{language.displayName}</span>
                    <small>
                      {language.imageRef} · {formatRunnerSize(language.estimatedCompressedSizeMb)}
                      {language.selectedByDefault ? " · default" : " · optional"} · {runnerImageSourceLabel(language.imageSource)}
                    </small>
                    {stageLabel.length > 0 ? <small>{stageLabel}</small> : null}
                    {language.lastError !== null ? <small className="form-error">{language.lastError}</small> : null}
                  </div>
                  <span className={`status-badge ${runnerStatusClass(language.status)}`}>{runnerStatusLabel(language)}</span>
                  <div className="mini-action-row">
                    <button
                      disabled={runnerLoading || rowPending}
                      onClick={() => void installRunner(language.language)}
                      type="button"
                    >
                      Install
                    </button>
                    <button
                      disabled={runnerLoading || rowPending}
                      onClick={() => void removeRunner(language.language)}
                      type="button"
                    >
                      Remove
                    </button>
                  </div>
                </div>
              );
            })}
            {runnerLanguages.length === 0 ? <p className="muted-copy">Runner language status is not loaded.</p> : null}
          </div>
        </div>
      </section>
    );
  }

  function renderCollectionPage() {
    return <section className="page-stack">{renderRepositorySettings()}</section>;
  }

  function renderEvidencePage() {
    return <section className="page-stack">{renderCollectedEvidencePanel()}</section>;
  }

  function renderTracePage() {
    return <section className="page-stack">{renderConversionTracePanel()}</section>;
  }

  function renderPracticePage() {
    return <section className="page-stack">{renderPracticeLibraryPanel()}</section>;
  }

  function renderRepositorySettings() {
    const counts = repositoryStatusCounts(localRepositories);
    const watcherCounts = localWatcherStatus?.watcherCounts ?? { active: 0, stopped: 0, degraded: 0, unavailable: 0 };
    const uploadQueue = localWatcherStatus?.uploadQueue ?? { queued: 0, discardedCount: 0, nextAttemptAt: null };
    const latestReconciliationAt =
      localWatcherStatus?.watchers
        .map((watcher) => watcher.lastReconciliationAt)
        .filter((value): value is string => typeof value === "string")
        .sort()
        .at(-1) ?? null;

    return (
      <section className="settings-section" aria-label="Collection status">
        <div className="panel-title">
          <FolderGit2 aria-hidden="true" size={20} />
          <h2>Collection Status</h2>
        </div>
        <div className="status-list">
          <span>{counts.approved} approved</span>
          <span>{counts.revoked} revoked</span>
          <span>{counts.always_ignored} ignored</span>
          <span>{counts.missing} missing</span>
        </div>
        <div className="watcher-toolbar">
          <div className="watcher-summary">
            <span>{localWatcherStatus?.collectionEnabled === false ? "disabled" : "enabled"}</span>
            <span>{watcherCounts.active} active</span>
            <span>{watcherCounts.degraded} degraded</span>
            <span>{uploadQueue.queued} queued</span>
            <span>{latestReconciliationAt === null ? "no reconciliation" : `last ${formatShortDate(latestReconciliationAt)}`}</span>
          </div>
          <div className="mini-action-row">
            <button className="secondary-button" disabled={localWatcherLoading} onClick={() => void refreshLocalWatcherStatus()} type="button">
              <RefreshCw aria-hidden="true" size={16} />
              Refresh
            </button>
            <button
              className="secondary-button"
              disabled={localWatcherLoading}
              onClick={() => void setWatcherCollectionEnabled(!(localWatcherStatus?.collectionEnabled ?? true))}
              type="button"
            >
              <ShieldCheck aria-hidden="true" size={16} />
              {localWatcherStatus?.collectionEnabled === false ? "Enable watcher" : "Disable watcher"}
            </button>
          </div>
        </div>
        {localWatcherError.length > 0 ? <p className="form-error">{localWatcherError}</p> : null}
        {localAdapterStatuses.length > 0 ? (
          <div className="watcher-list" aria-label="Adapter status">
            {localAdapterStatuses.map((adapter) => (
              <div className="watcher-row" key={adapter.provider}>
                <div>
                  <strong>{adapter.label}</strong>
                  <small>
                    {adapter.reason === null ? adapter.provider : `${adapter.provider} · ${adapter.reason.replaceAll("_", " ")}`}
                    {adapter.lastEventAt === null ? "" : ` · ${formatShortDate(adapter.lastEventAt)}`}
                  </small>
                </div>
                <span className={`status-badge ${adapterStatusClass(adapter)}`}>{adapter.status}</span>
              </div>
            ))}
          </div>
        ) : null}
        <div className="retention-policy-row" aria-label="Raw evidence retention">
          <div className="setting-copy">
            <strong>Raw Evidence Retention</strong>
            <small>{retentionSummary(retentionSettings)} · metadata, generated cards, and practice progress remain after raw purge</small>
          </div>
          <div className="retention-controls">
            <select
              aria-label="Retention mode"
              disabled={retentionLoading}
              onChange={(event) => setRetentionMode(event.target.value as EvidenceRetentionMode)}
              value={retentionMode}
            >
              <option value="default">Default cleanup</option>
              <option value="disabled">Disabled</option>
              <option value="immediate">Immediate purge</option>
            </select>
            {retentionMode === "default" ? (
              <input
                aria-label="Retention days"
                disabled={retentionLoading}
                max={3650}
                min={1}
                onChange={(event) => setRetentionDays(event.target.value)}
                type="number"
                value={retentionDays}
              />
            ) : null}
            <button className="secondary-button" disabled={retentionLoading} onClick={() => void saveRetentionSettings()} type="button">
              <ShieldCheck aria-hidden="true" size={16} />
              Save
            </button>
            <button className="secondary-button danger-button" disabled={retentionLoading} onClick={() => void purgeAllRawEvidenceNow()} type="button">
              <Trash2 aria-hidden="true" size={16} />
              Purge now
            </button>
          </div>
        </div>
        {retentionMessage.length > 0 ? <p className="muted-copy">{retentionMessage}</p> : null}
        <div className="watcher-list" aria-live="polite">
          {localWatcherStatus?.watchers.length === 0 ? <p className="muted-copy">No watcher registrations.</p> : null}
          {localWatcherStatus?.watchers.map((watcher) => (
            <div className="watcher-row" key={watcher.repoIdentityHash}>
              <div>
                <strong>{watcher.repositoryDisplayLabel}</strong>
                <small>
                  {watcher.lastReconciliationAt === null ? "not reconciled" : formatShortDate(watcher.lastReconciliationAt)}
                  {" · "}
                  {watcher.lastReconciliationStatus ?? "status unknown"}
                  {" · "}
                  {watcher.lastReconciliationDiffCandidateCount} diff candidates
                  {watcher.reason !== null ? ` · ${watcher.reason}` : ""}
                </small>
              </div>
              <span className={`status-badge ${watcherStatusClass(watcher)}`}>{watcherStateLabel(watcher)}</span>
            </div>
          ))}
        </div>
        <div className="repository-approval-row">
          <button className="path-picker-button" disabled={repositoryPickerLoading} onClick={() => void chooseRepositoryPath()} type="button">
            <FolderOpen aria-hidden="true" size={16} />
            <span>{repositoryLabel.trim().length > 0 ? repositoryLabel : "Select repository folder"}</span>
          </button>
          <button className="primary-button" disabled={repositoryLabel.trim().length === 0 || repositoryPickerLoading} onClick={() => void approveRepository()} type="button">
            <CheckCircle2 aria-hidden="true" size={16} />
            Approve
          </button>
        </div>
        <div className="repository-list" aria-live="polite">
          {localRepositories.length === 0 ? <p className="muted-copy">No repositories configured.</p> : null}
          {localRepositories.map((repository) => (
            <div className="repository-row" key={repository.repoIdentityHash}>
              <div>
                <strong>{repository.displayLabel}</strong>
                <small>{repositoryStatusLabel(repository.status)} · {formatShortDate(repository.updatedAt)}</small>
                <small>{repository.repoRoot ?? "Repository path needs reselecting."}</small>
              </div>
              <div className="mini-action-row">
                <button onClick={() => void updateRepositoryStatus(repository, "approved")} type="button">Approve</button>
                <button onClick={() => void updateRepositoryStatus(repository, "revoked")} type="button">Revoke</button>
                <button onClick={() => void updateRepositoryStatus(repository, "always_ignored")} type="button">Ignore</button>
                <button onClick={() => void updateRepositoryStatus(repository, "missing")} type="button">Missing</button>
              </div>
            </div>
          ))}
        </div>
      </section>
    );
  }

  function renderCollectedEvidencePanel() {
    const selectedBundle = evidenceList.bundles.find((bundle) => bundle.id === selectedEvidenceId) ?? null;
    const maxPage = Math.max(0, Math.ceil(evidenceList.total / evidenceList.pageSize) - 1);

    return (
      <div className="panel panel-wide evidence-panel">
        <div className="panel-title">
          <Database aria-hidden="true" size={20} />
          <h2>Collected Evidence</h2>
        </div>
        <div className="action-row">
          <button className="secondary-button" disabled={evidenceLoading} onClick={() => session !== null && void refreshEvidence(session)} type="button">
            <RefreshCw aria-hidden="true" size={16} />
            {evidenceLoading ? "Loading" : "Refresh"}
          </button>
          <label className="inline-control">
            <span>Generation provider</span>
            <select
              disabled={generationProviders.length === 0}
              onChange={(event) => setSelectedGenerationProviderId(event.target.value)}
              value={selectedGenerationProviderId}
            >
              {generationProviders.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.provider} · {provider.model}
                </option>
              ))}
            </select>
          </label>
          <button
            className="secondary-button"
            disabled={evidenceList.page === 0 || evidenceLoading || session === null}
            onClick={() => session !== null && void refreshEvidence(session, evidenceList.page - 1)}
            type="button"
          >
            <ChevronLeft aria-hidden="true" size={16} />
            Previous
          </button>
          <button
            className="secondary-button"
            disabled={evidenceList.page >= maxPage || evidenceLoading || session === null}
            onClick={() => session !== null && void refreshEvidence(session, evidenceList.page + 1)}
            type="button"
          >
            Next
            <ChevronRight aria-hidden="true" size={16} />
          </button>
          <span className="pagination-status">{evidenceList.total} bundles</span>
        </div>
        {evidenceError.length > 0 ? <p className="form-error">{evidenceError}</p> : null}
        {evidenceActionMessage.length > 0 ? <p className="action-message">{evidenceActionMessage}</p> : null}
        <div className="evidence-browser">
          <div className="evidence-list" aria-label="Collected evidence list">
            {evidenceList.bundles.length === 0 && !evidenceLoading ? <p className="muted-copy">No collected evidence yet.</p> : null}
            {evidenceList.bundles.map((bundle) => (
              <button
                className={bundle.id === selectedEvidenceId ? "evidence-row evidence-row-active" : "evidence-row"}
                key={bundle.id}
                onClick={() => void selectEvidence(bundle.id)}
                type="button"
              >
                <span>
                  <strong>{bundle.title}</strong>
                  <small>{bundle.sourceKind} · {formatShortDate(bundle.createdAt)}</small>
                </span>
                <span className={`status-badge ${bundleStatusClass(bundle)}`}>{bundleCurationLabel(bundle)}</span>
              </button>
            ))}
          </div>
          <div className="evidence-detail" aria-live="polite">
            {selectedBundle === null ? <p className="muted-copy">Select evidence to view bounded excerpts.</p> : null}
            {selectedBundle !== null ? renderEvidenceSummary(selectedBundle) : null}
            {evidenceDetailLoading ? <p className="muted-copy">Loading evidence excerpts.</p> : null}
            {selectedBundle !== null && selectedEvidenceDetail?.bundle.id === selectedBundle.id ? renderEvidenceItems(selectedEvidenceDetail.evidenceItems) : null}
          </div>
        </div>
      </div>
    );
  }

  function renderConversionTracePanel() {
    const selectedTrace = conversionTraces.find((trace) => trace.generationRunId === selectedTraceId) ?? conversionTraces[0] ?? null;

    return (
      <div className="panel panel-wide trace-panel">
        <div className="panel-title">
          <GitPullRequest aria-hidden="true" size={20} />
          <h2>Conversion Trace</h2>
        </div>
        {conversionTraceError.length > 0 ? <p className="form-error">{conversionTraceError}</p> : null}
        <div className="evidence-browser trace-browser">
          <div className="evidence-list" aria-label="Conversion trace list">
            {conversionTraces.length === 0 && conversionTraceError.length === 0 ? <p className="muted-copy">No conversion traces yet.</p> : null}
            {conversionTraces.map((trace) => {
              const isSelected = selectedTrace?.generationRunId === trace.generationRunId;
              return (
                <button
                  className={isSelected ? "evidence-row evidence-row-active" : "evidence-row"}
                  key={trace.generationRunId}
                  onClick={() => setSelectedTraceId(trace.generationRunId)}
                  type="button"
                >
                  <span>
                    <strong>{trace.source?.codeTitle ?? trace.pattern?.title ?? (trace.status === "failed" ? "Failed generation" : "Unlinked trace")}</strong>
                    <small>{trace.pattern?.title ?? trace.failureCode ?? trace.status} · {formatShortDate(trace.createdAt)}</small>
                  </span>
                  <span className={`status-badge ${traceBadgeClass(trace)}`}>{exerciseStateLabel(trace)}</span>
                </button>
              );
            })}
          </div>
          <div className="evidence-detail trace-detail" aria-live="polite">
            {selectedTrace === null ? <p className="muted-copy">Select a trace to inspect its source, pattern, and exercise state.</p> : null}
            {selectedTrace !== null ? (
              <>
                <div className="trace-detail-section">
                  <strong>Source</strong>
                  <span>{selectedTrace.source?.codeTitle ?? "Unlinked source"}</span>
                  <small>{selectedTrace.source?.conversationTitle ?? selectedTrace.source?.sourceLinkStatus ?? selectedTrace.status}</small>
                  {selectedTrace.source !== null && selectedTrace.source.confidence !== null ? (
                    <div className="evidence-meta-grid">
                      <span>Confidence {Math.round(selectedTrace.source.confidence * 100)}%</span>
                      <span>{selectedTrace.source.codeSourceKind ?? "source kind unknown"}</span>
                    </div>
                  ) : null}
                </div>
                <div className="trace-detail-section">
                  <strong>Pattern</strong>
                  <span>{selectedTrace.pattern?.title ?? (selectedTrace.status === "failed" ? "Generation failed" : "Pattern pending")}</span>
                  <small>{selectedTrace.pattern?.summary ?? selectedTrace.failureCode ?? "No pattern summary yet."}</small>
                  {selectedTrace.pattern?.tags.length ? (
                    <div className="tag-row">
                      {selectedTrace.pattern.tags.slice(0, 6).map((tag) => (
                        <span key={`${selectedTrace.generationRunId}:${tag.tagType}:${tag.name}`}>{tag.name}</span>
                      ))}
                    </div>
                  ) : null}
                </div>
                <div className="trace-detail-section">
                  <strong>Exercise</strong>
                  <span>{exerciseStateLabel(selectedTrace)}</span>
                  <small>
                    {selectedTrace.exercise === null
                      ? "No exercise state"
                      : `${selectedTrace.exercise.problemCount} exercises · ${selectedTrace.exercise.difficulties.join(", ") || "difficulty pending"}`}
                  </small>
                  {selectedTrace.exercise !== null ? (
                    <div className="evidence-meta-grid">
                      <span>{selectedTrace.exercise.publicationStatus}</span>
                      <span>{selectedTrace.exercise.reviewStatus ?? "review status unknown"}</span>
                    </div>
                  ) : null}
                </div>
              </>
            ) : null}
          </div>
        </div>
      </div>
    );
  }

  function renderPracticeLibraryPanel() {
    const selectedCard = libraryCards.find((card) => card.id === selectedPracticeCardId) ?? libraryCards[0] ?? null;
    const activePracticeBelongsToSelectedCard = activePractice !== null && activePractice.patternCardId === selectedCard?.id;

    return (
      <div className="panel panel-wide practice-library-panel">
        <div className="panel-title">
          <BookOpen aria-hidden="true" size={20} />
          <h2>Practice Library</h2>
        </div>
        <div className="library-filters" aria-label="Practice filters">
          <label>
            <span>Language</span>
            <select value={libraryFilters.language} onChange={(event) => updateLibraryFilter("language", event.target.value)}>
              <option value="">Any</option>
              <option value="TypeScript">TypeScript</option>
              <option value="Kotlin">Kotlin</option>
              <option value="Java">Java</option>
              <option value="Swift">Swift</option>
              <option value="Rust">Rust</option>
            </select>
          </label>
          <label>
            <span>Pattern/API</span>
            <input
              onChange={(event) => updateLibraryFilter("tag", event.target.value)}
              placeholder="Pure Function"
              type="search"
              value={libraryFilters.tag}
            />
          </label>
          <label>
            <span>Difficulty</span>
            <select value={libraryFilters.difficulty} onChange={(event) => updateLibraryFilter("difficulty", event.target.value)}>
              <option value="">Any</option>
              <option value="easy">Easy</option>
              <option value="medium">Medium</option>
              <option value="hard">Hard</option>
            </select>
          </label>
          <label>
            <span>Status</span>
            <select
              value={libraryFilters.publicationStatus}
              onChange={(event) => updateLibraryFilter("publicationStatus", event.target.value as LibraryFilters["publicationStatus"])}
            >
              <option value="published">Published</option>
            </select>
          </label>
        </div>

        {libraryError.length > 0 ? <p className="form-error">{libraryError}</p> : null}
        <div className="evidence-browser practice-browser">
          <div className="evidence-list practice-list" aria-label="Practice card list">
            {libraryLoading ? <p className="muted-copy">Loading practice cards.</p> : null}
            {!libraryLoading && libraryError.length === 0 && libraryCards.length === 0 ? (
              <p className="muted-copy">No practice cards match these filters.</p>
            ) : null}
            {!libraryLoading && libraryError.length === 0
              ? libraryCards.map((card) => {
                  const isSelected = selectedCard?.id === card.id;
                  return (
                    <button
                      className={isSelected ? "evidence-row evidence-row-active practice-list-row" : "evidence-row practice-list-row"}
                      key={card.id}
                      onClick={() => setSelectedPracticeCardId(card.id)}
                      type="button"
                    >
                      <span>
                        <strong>{card.title}</strong>
                        <small>{card.summary}</small>
                      </span>
                      <span className="status-badge status-neutral">{card.problems[0]?.difficulty ?? "practice"}</span>
                    </button>
                  );
                })
              : null}
          </div>
          <div className="evidence-detail practice-detail" aria-live="polite">
            {selectedCard === null ? <p className="muted-copy">Select a practice card to view problems and open the workbench.</p> : null}
            {selectedCard !== null ? (
              <>
                <div className="practice-detail-summary">
                  <div className="practice-card-header">
                    <strong>{selectedCard.title}</strong>
                    <span>{selectedCard.problems[0]?.difficulty ?? "practice"}</span>
                  </div>
                  <p>{selectedCard.summary}</p>
                  <div className="tag-row">
                    {selectedCard.tags.slice(0, 6).map((tag) => (
                      <span key={`${selectedCard.id}:${tag.tagType}:${tag.name}`}>{tag.name}</span>
                    ))}
                  </div>
                  <div className="problem-list problem-action-list">
                    {selectedCard.problems.map((problem) => (
                      <button
                        className="secondary-button"
                        key={problem.id}
                        onClick={() => void openPractice(problem.id)}
                        type="button"
                      >
                        <BookOpen aria-hidden="true" size={16} />
                        {problem.type}
                      </button>
                    ))}
                  </div>
                </div>
                {activePracticeBelongsToSelectedCard ? renderPracticeWorkbenchPanels() : (
                  <div className="empty-detail-panel">
                    <Library aria-hidden="true" size={20} />
                    <p className="muted-copy">Open a problem from this card to start the workbench.</p>
                  </div>
                )}
              </>
            ) : null}
          </div>
        </div>
      </div>
    );
  }

  function renderPracticeWorkbenchPanels() {
    return (
      <section className="practice-workbench" id="practice">
        <div className="panel practice-main-panel">
          <div className="panel-title">
            <Library aria-hidden="true" size={20} />
            <h2>Practice Workbench</h2>
          </div>
          {practiceLoading ? <p className="muted-copy">Loading practice problem.</p> : null}
          {practiceError.length > 0 ? <p className="form-error">{practiceError}</p> : null}
          {!practiceLoading && practiceError.length === 0 && activePractice === null ? (
            <p className="muted-copy">Open a practice card to start.</p>
          ) : null}
          {!practiceLoading && practiceError.length === 0 && activePractice !== null ? (
            <div className="practice-shell">
              <div className="practice-statement">
                <div>
                  <p className="eyebrow">{activePractice.difficulty} · {activePractice.assetRevision}</p>
                  <h3>{activePractice.title}</h3>
                </div>
                <p>{activePractice.prompt}</p>
              </div>
              <div className="editor-shell-placeholder" aria-label="Practice files">
                <div className="editor-topbar">
                  <div className="file-strip" role="tablist" aria-label="Practice files">
                    {activePractice.files.map((file) => (
                      <button
                        aria-selected={(activePracticePath ?? activePractice.files[0]?.path ?? null) === file.path}
                        className={(activePracticePath ?? activePractice.files[0]?.path ?? null) === file.path ? "file-tab active" : "file-tab"}
                        key={file.path}
                        onClick={() => setActivePracticePath(file.path)}
                        role="tab"
                        title={file.readOnly ? `${file.path} (read-only)` : file.path}
                        type="button"
                      >
                        <span>{file.path}</span>
                        {file.readOnly ? <LockKeyhole aria-hidden="true" size={12} /> : null}
                      </button>
                    ))}
                  </div>
                  <span className={`save-status ${practiceSaveStatus.state}`} aria-live="polite">
                    {practiceSaveStatus.message}
                  </span>
                  <button
                    aria-label="Run tests"
                    className="editor-tool-button"
                    disabled={practiceSaveStatus.state === "running"}
                    onClick={() => {
                      const snapshot = editorSnapshotRef.current;
                      if (snapshot === null) {
                        setPracticeSaveStatus({ state: "idle", message: "Editor is still loading" });
                        return;
                      }
                      void runPracticeSolution(snapshot());
                    }}
                    title="Run tests"
                    type="button"
                  >
                    <Play aria-hidden="true" size={16} />
                  </button>
                  <button
                    aria-label={editorTheme === "vs-dark" ? "Switch editor to light theme" : "Switch editor to dark theme"}
                    className="editor-tool-button"
                    onClick={toggleEditorTheme}
                    title={editorTheme === "vs-dark" ? "Light editor theme" : "Dark editor theme"}
                    type="button"
                  >
                    {editorTheme === "vs-dark" ? <Sun aria-hidden="true" size={16} /> : <Moon aria-hidden="true" size={16} />}
                  </button>
                </div>
                <Suspense fallback={<pre>{activePractice.files[0]?.content ?? "// Loading editor bundle."}</pre>}>
                  <PracticeEditorShell
                    activePath={activePracticePath ?? activePractice.files[0]?.path ?? null}
                    files={activePractice.files}
                    onCommandPalette={openCommandPalette}
                    onOpenQuickFile={openQuickOpen}
                    onRun={(files) => {
                      void runPracticeSolution(files);
                    }}
                    onSave={(files) => {
                      void savePracticeDraft(files);
                    }}
                    onSnapshotReady={(snapshotter) => {
                      editorSnapshotRef.current = snapshotter;
                    }}
                    onStatus={showEditorStatus}
                    onSubmit={(files) => {
                      void submitPracticeSolution(files);
                    }}
                    onToggleDiff={togglePracticeDiff}
                    onToggleTheme={toggleEditorTheme}
                    theme={editorTheme}
                  />
                </Suspense>
                {diffVisible && activePractice.latestRun?.failedDiff != null ? (
                  <pre className="diff-panel">{activePractice.latestRun.failedDiff}</pre>
                ) : null}
                {answerDiff !== null ? (
                  <Suspense fallback={<pre className="answer-diff-fallback">Loading answer diff.</pre>}>
                    <PracticeAnswerDiff
                      language={answerDiff.language}
                      path={answerDiff.path}
                      referenceAnswer={answerDiff.referenceAnswer}
                      submittedAnswer={answerDiff.submittedAnswer}
                      theme={editorTheme}
                    />
                  </Suspense>
                ) : null}
                {workbenchOverlay !== null ? renderWorkbenchOverlay(activePractice) : null}
              </div>
            </div>
          ) : null}
        </div>

        <div className="panel practice-side-panel">
          <div className="panel-title">
            <ListChecks aria-hidden="true" size={20} />
            <h2>Guidance</h2>
          </div>
          {activePractice === null ? (
            <p className="muted-copy">Hints, provenance, and feedback appear after a problem opens.</p>
          ) : (
            <div className="guidance-stack">
              <div>
                <strong>Hints</strong>
                <div className="review-table">
                  {activePractice.hints.map((hint) => (
                    <div className="guidance-row hint-row" key={hint.id}>
                      <span>{hint.label}</span>
                      {isHintRevealed(hint) ? <small>{hint.content}</small> : <small>Locked</small>}
                      {!isHintRevealed(hint) ? (
                        <button disabled={!canRevealHint(hint)} onClick={() => revealHint(hint)} type="button">
                          Reveal
                        </button>
                      ) : null}
                    </div>
                  ))}
                </div>
              </div>
              <div className="collapsible-section">
                <button
                  aria-expanded={provenanceExpanded}
                  className="section-toggle"
                  onClick={() => setProvenanceExpanded((current) => !current)}
                  type="button"
                >
                  <strong>Provenance</strong>
                  {provenanceExpanded ? <ChevronDown aria-hidden="true" size={16} /> : <ChevronRight aria-hidden="true" size={16} />}
                </button>
                {provenanceExpanded ? (
                  <div className="review-table">
                    {activePractice.provenance.map((source) => (
                      <div className="guidance-row" key={`${source.sourceType}:${source.sourceLabel}`}>
                        <span>{source.sourceLabel}</span>
                        <small>{source.sourceType}: {source.redactedExcerpt}</small>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
              {renderFeedbackPanel(activePractice)}
            </div>
          )}
        </div>
      </section>
    );
  }

  function renderEvidenceSummary(bundle: SourceBundleSummaryResponse) {
    const canUseForGeneration = bundle.sourceKind === "local_ai_session" && bundle.status === "generation_eligible";
    const canGenerate = canUseForGeneration && bundle.userAttribution === "use_for_generation" && selectedGenerationProvider !== null;
    const isGuiCorrelated = isGuiCorrelatedEvidence(bundle);
    const blockedReason = generationBlockedReason(bundle);
    const reasons = reasonCodes(bundle.attributionReasonsJson);

    return (
      <div className="evidence-summary">
        <div>
          <strong>{bundle.title}</strong>
          <small>{bundle.repositoryUrl ?? "local evidence"} · {bundle.branchName ?? "branch unknown"}</small>
        </div>
        <div className="evidence-meta-grid">
          <span>Auto: {attributionLabel(bundle.autoAttribution)}</span>
          <span>User: {attributionLabel(bundle.userAttribution ?? "uncurated")}</span>
          <span>Confidence: {bundle.attributionConfidence === null ? "unknown" : bundle.attributionConfidence.toFixed(2)}</span>
          <span>{reasons.map(reasonCodeLabel).join(", ") || "no reason codes"}</span>
        </div>
        {isGuiCorrelated ? (
          <div className="evidence-notice">
            <ShieldCheck aria-hidden="true" size={16} />
            <span>{guiCorrelationExplanation(bundle)}</span>
          </div>
        ) : null}
        {blockedReason !== null ? <small className="evidence-blocked-copy">{blockedReason}</small> : null}
        <div className="mini-action-row">
          {isGuiCorrelated ? (
            <button disabled={bundle.userAttribution === "manual"} onClick={() => void confirmGuiCorrelation(bundle)} type="button">
              <CheckCircle2 aria-hidden="true" size={14} />
              Confirm
            </button>
          ) : (
            <button disabled={!canUseForGeneration} onClick={() => void applyEvidenceAttribution(bundle, "use_for_generation")} type="button">
              <CheckCircle2 aria-hidden="true" size={14} />
              Use
            </button>
          )}
          <button onClick={() => void applyEvidenceAttribution(bundle, "manual")} type="button">
            <Bot aria-hidden="true" size={14} />
            Manual
          </button>
          <button disabled={!canGenerate} onClick={() => void generatePracticeFromEvidence(bundle)} type="button">
            <Sparkles aria-hidden="true" size={14} />
            Generate
          </button>
          <button onClick={() => void purgeEvidence(bundle)} type="button">
            <Eye aria-hidden="true" size={14} />
            Purge raw
          </button>
          <button onClick={() => void removeEvidence(bundle)} type="button">
            <Trash2 aria-hidden="true" size={14} />
            Delete
          </button>
        </div>
      </div>
    );
  }

  function renderEvidenceItems(items: EvidenceItemResponse[]) {
    if (items.length === 0) {
      return <p className="muted-copy">No evidence items available.</p>;
    }

    return (
      <div className="evidence-items">
        {items.map((item) => (
          <div className="evidence-item" key={item.id}>
            <div>
              <strong>{item.itemType}</strong>
              <small>{item.repoRelativePath ?? "session artifact"} · {formatBytes(item.sizeBytes)}</small>
            </div>
            {item.contentText === null ? (
              <small className="muted-copy">Raw content unavailable.</small>
            ) : (
              <pre>{item.contentText}</pre>
            )}
          </div>
        ))}
      </div>
    );
  }

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAuthError("");

    try {
      const nextSession = await createSession(email, password);
      if (!isLocalOwnerSession(nextSession)) {
        storeSession(null);
        setSession(null);
        setAuthError("Sign in with the local owner account.");
        return;
      }

      setSession(nextSession);
      setPassword("");
      const storedSettings = readLocalAiSettings(nextSession.user.id);
      setLocalAiSettings(storedSettings);
      if (storedSettings === null) {
        openLocalAiSetup();
      } else {
        setActivePage("dashboard");
      }
      await refreshProviders(nextSession);
    } catch (error) {
      setAuthError(error instanceof Error ? error.message : "Authentication failed");
    }
  }

  async function refreshProviders(currentSession: SessionResponse) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setProviders([]);
      return;
    }
    const nextProviders = await listProviders(currentSession.token, currentMembership.organizationId);
    setProviders(nextProviders);
    const preferredProviderId = readLocalAiSettings(currentSession.user.id)?.providerConfigId;
    const preferredProvider = nextProviders.find((provider) => provider.id === preferredProviderId && provider.status === "active");
    if (preferredProvider !== undefined) {
      setSelectedGenerationProviderId(preferredProvider.id);
    }
  }

  async function loadRunnerLanguages(
    currentSession: SessionResponse,
    refresh = false,
  ) {
    setRunnerLoading(true);
    setRunnerMessage(refresh ? "Checking local runner images" : "");
    try {
      const languages = refresh ? await refreshRunnerLanguages(currentSession.token) : await listRunnerLanguages(currentSession.token);
      setRunnerLanguages(languages);
      setRunnerMessage(refresh ? "Runner status refreshed" : "");
    } catch (error) {
      setRunnerMessage(error instanceof Error ? error.message : "Runner status failed to load");
    } finally {
      setRunnerLoading(false);
    }
  }

  async function pollRunnerLanguages(currentSession: SessionResponse) {
    try {
      const languages = await listRunnerLanguages(currentSession.token);
      setRunnerLanguages(languages);
    } catch {
      // The explicit Refresh action surfaces errors; polling should not interrupt the page.
    }
  }

  async function installRunner(language: string) {
    if (session === null) return;
    setRunnerPendingLanguages((current) => new Set(current).add(language));
    setRunnerMessage(`Installing ${language} runner`);
    try {
      const updated = await installRunnerLanguage(session.token, language);
      setRunnerLanguages((current) => replaceRunnerLanguage(current, updated));
      setRunnerMessage(`${updated.displayName} runner ${updated.status}`);
    } catch (error) {
      setRunnerMessage(error instanceof Error ? error.message : "Runner install failed");
    } finally {
      setRunnerPendingLanguages((current) => {
        const next = new Set(current);
        next.delete(language);
        return next;
      });
    }
  }

  async function removeRunner(language: string) {
    if (session === null) return;
    setRunnerPendingLanguages((current) => new Set(current).add(language));
    setRunnerMessage(`Removing ${language} runner`);
    try {
      const updated = await removeRunnerLanguage(session.token, language);
      setRunnerLanguages((current) => replaceRunnerLanguage(current, updated));
      setRunnerMessage(`${updated.displayName} runner ${updated.status}`);
    } catch (error) {
      setRunnerMessage(error instanceof Error ? error.message : "Runner remove failed");
    } finally {
      setRunnerPendingLanguages((current) => {
        const next = new Set(current);
        next.delete(language);
        return next;
      });
    }
  }

  async function refreshLibrary(currentSession: SessionResponse) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setLibraryCards([]);
      return;
    }

    setLibraryLoading(true);
    setLibraryError("");
    try {
      const cards = await getLibrary(currentSession.token, currentMembership.organizationId, {
        language: libraryFilters.language,
        tag: libraryFilters.tag,
        difficulty: libraryFilters.difficulty,
        page: 0,
        pageSize: 100
      });
      setLibraryCards(cards.filter((card) => card.publicationStatus === libraryFilters.publicationStatus));
    } catch (error) {
      setLibraryError(error instanceof Error ? error.message : "Library failed to load");
      setLibraryCards([]);
    } finally {
      setLibraryLoading(false);
    }
  }

  async function refreshLearningSignals(currentSession: SessionResponse) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setProgressScores([]);
      setRecommendationCards([]);
      return;
    }

    try {
      const [progress, recommendations] = await Promise.all([
        getProgress(currentSession.token, currentMembership.organizationId),
        getRecommendations(currentSession.token, currentMembership.organizationId)
      ]);
      setProgressScores(progress);
      setRecommendationCards(recommendations);
    } catch {
      setProgressScores([]);
      setRecommendationCards([]);
    }
  }

  async function refreshConversionTraces(currentSession: SessionResponse) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setConversionTraces([]);
      setConversionTraceError("");
      return;
    }

    try {
      setConversionTraceError("");
      setConversionTraces(await getConversionTraces(currentSession.token, currentMembership.organizationId));
    } catch (error) {
      setConversionTraces([]);
      setConversionTraceError(error instanceof Error ? error.message : "Conversion traces failed to load");
    }
  }

  async function refreshEvidence(currentSession: SessionResponse, page = evidenceList.page) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setEvidenceList({ bundles: [], page: 0, pageSize: 50, total: 0 });
      setSelectedEvidenceId(null);
      setSelectedEvidenceDetail(null);
      return;
    }

    setEvidenceLoading(true);
    setEvidenceError("");
    try {
      const list = await listEvidence(currentSession.token, currentMembership.organizationId, page, evidenceList.pageSize);
      setEvidenceList(list);
      if (selectedEvidenceId !== null && !list.bundles.some((bundle) => bundle.id === selectedEvidenceId)) {
        evidenceDetailRequestRef.current += 1;
        setSelectedEvidenceId(null);
        setSelectedEvidenceDetail(null);
      }
    } catch (error) {
      setEvidenceList({ bundles: [], page: 0, pageSize: 50, total: 0 });
      setEvidenceError(error instanceof Error ? error.message : "Collected evidence failed to load");
    } finally {
      setEvidenceLoading(false);
    }
  }

  async function selectEvidence(bundleId: string) {
    if (session === null) return;
    const requestId = evidenceDetailRequestRef.current + 1;
    evidenceDetailRequestRef.current = requestId;
    setSelectedEvidenceId(bundleId);
    setSelectedEvidenceDetail(null);
    setEvidenceDetailLoading(true);
    setOnboardingError("");
    try {
      const detail = await getEvidenceDetail(session.token, bundleId);
      if (evidenceDetailRequestRef.current === requestId) {
        setSelectedEvidenceDetail(detail);
      }
    } catch (error) {
      if (evidenceDetailRequestRef.current === requestId) {
        setEvidenceActionMessage(error instanceof Error ? error.message : "Evidence detail failed to load");
      }
    } finally {
      if (evidenceDetailRequestRef.current === requestId) {
        setEvidenceDetailLoading(false);
      }
    }
  }

  async function refreshLocalRepositories(currentSession: SessionResponse) {
    const currentMembership = primaryMembership(currentSession);
    if (currentMembership === null) {
      setLocalRepositories([]);
      setRetentionSettings(null);
      return;
    }

    try {
      const repositories = await listLocalRepositories(currentSession.token, currentMembership.organizationId);
      const localConsents = repositories.map(toLocalRepositoryConsent);
      setLocalRepositories(localConsents);
      await syncApprovedLocalRepositories(localConsents);
    } catch {
      setLocalRepositories([]);
    }
    await refreshRetentionSettings(currentSession, currentMembership.organizationId);
  }

  async function syncApprovedLocalRepositories(repositories: LocalRepositoryConsent[]) {
    const approvedRepositories = repositories.filter((repository) => repository.status === "approved" && repository.repoRoot !== null);
    for (const repository of approvedRepositories) {
      await syncCompanionWatcherRepository(repository.repoIdentityHash, repository.displayLabel, repository.status, repository.repoRoot ?? undefined, false);
    }
    if (approvedRepositories.length > 0) {
      await refreshLocalWatcherStatus();
    }
  }

  async function refreshRetentionSettings(currentSession: SessionResponse, organizationId: string) {
    try {
      const settings = await getEvidenceRetentionSettings(currentSession.token, organizationId);
      setRetentionSettings(settings);
      setRetentionMode(settings.retentionMode);
      setRetentionDays(retentionDaysFormValue(settings));
    } catch {
      setRetentionSettings(null);
    }
  }

  async function refreshLocalWatcherStatus() {
    setLocalWatcherLoading(true);
    setLocalWatcherError("");
    try {
      const localToken = await readLocalCompanionToken();
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/watchers/status`, {
        headers: { "x-learnloop-local-token": localToken }
      });
      const payload = (await response.json().catch(() => ({}))) as CompanionWatcherStatusResponse;
      if (!response.ok) throw new Error(payload.message || "Watcher status is unavailable");
      setLocalWatcherStatus(toLocalWatcherStatus(payload));
      await refreshLocalAdapterStatus(localToken);
    } catch (error) {
      setLocalWatcherStatus(null);
      setLocalAdapterStatuses([]);
      setLocalWatcherError(error instanceof Error ? error.message : "Watcher status is unavailable");
    } finally {
      setLocalWatcherLoading(false);
    }
  }

  async function refreshLocalAdapterStatus(localToken: string) {
    try {
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/adapters/status`, {
        headers: { "x-learnloop-local-token": localToken }
      });
      const payload = (await response.json().catch(() => ({}))) as CompanionAdapterStatusResponse;
      setLocalAdapterStatuses(response.ok ? toLocalAdapterStatuses(payload) : []);
    } catch {
      setLocalAdapterStatuses([]);
    }
  }

  async function setWatcherCollectionEnabled(enabled: boolean) {
    setLocalWatcherLoading(true);
    setLocalWatcherError("");
    try {
      const localToken = await readLocalCompanionToken();
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/watchers/settings`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-learnloop-local-token": localToken },
        body: JSON.stringify({ enabled })
      });
      const payload = (await response.json().catch(() => ({}))) as CompanionWatcherStatusResponse;
      if (!response.ok) throw new Error(payload.message || "Watcher setting update failed");
      setLocalWatcherStatus(toLocalWatcherStatus(payload));
      await refreshLocalAdapterStatus(localToken);
    } catch (error) {
      setLocalWatcherError(error instanceof Error ? error.message : "Watcher setting update failed");
    } finally {
      setLocalWatcherLoading(false);
    }
  }

  async function syncCompanionWatcherRepository(
    repoIdentityHash: string,
    displayLabel: string,
    status: RepositoryConsentStatus,
    repoRoot?: string,
    refreshAfter = true
  ) {
    try {
      const localToken = await readLocalCompanionToken();
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/watchers/repositories`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-learnloop-local-token": localToken },
        body: JSON.stringify({
          repoIdentityHash,
          repositoryDisplayLabel: displayLabel,
          repoRoot,
          status
        })
      });
      const payload = (await response.json().catch(() => ({}))) as CompanionWatcherStatusResponse;
      if (!response.ok) throw new Error(payload.message || "Watcher repository sync failed");
      if (refreshAfter) {
        await refreshLocalWatcherStatus();
      }
    } catch (error) {
      setLocalWatcherError(error instanceof Error ? error.message : "Watcher repository sync failed");
    }
  }

  async function chooseRepositoryPath() {
    setLocalWatcherError("");
    setOnboardingError("");
    setRepositoryPickerLoading(true);
    try {
      const localToken = await readLocalCompanionToken();
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/host/directory-picker`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-learnloop-local-token": localToken },
        body: JSON.stringify({ prompt: "Choose a repository folder for LearnLoop collection." })
      });
      const payload = (await response.json().catch(() => ({}))) as CompanionDirectoryPickerResponse;
      if (!response.ok) throw new Error(payload.message || "Repository folder picker failed");
      if (payload.status === "cancelled") return;
      if (typeof payload.path !== "string" || payload.path.length === 0) {
        throw new Error("No repository folder was selected");
      }
      setRepositoryLabel(payload.path);
    } catch (error) {
      setLocalWatcherError(error instanceof Error ? error.message : "Repository folder picker failed");
    } finally {
      setRepositoryPickerLoading(false);
    }
  }

  async function approveRepository() {
    if (session === null || membership === null) return;
    const label = repositoryLabel.trim();
    if (label.length === 0) return;
    setEvidenceActionMessage("");
    try {
      const repoIdentityHash = await repositoryIdentityHash(label);
      await updateLocalRepository(session.token, repoIdentityHash, {
        organizationId: membership.organizationId,
        displayLabel: repositoryDisplayLabel(label),
        repoRoot: label,
        status: "approved"
      });
      await syncCompanionWatcherRepository(repoIdentityHash, repositoryDisplayLabel(label), "approved", label);
      await refreshLocalRepositories(session);
      setRepositoryLabel("");
    } catch (error) {
      setOnboardingError(error instanceof Error ? error.message : "Repository approval failed");
    }
  }

  async function updateRepositoryStatus(repository: LocalRepositoryConsent, status: RepositoryConsentStatus) {
    if (session === null || membership === null) return;
    setOnboardingError("");
    try {
      await updateLocalRepository(session.token, repository.repoIdentityHash, {
        organizationId: membership.organizationId,
        displayLabel: repository.displayLabel,
        ...(repository.repoRoot !== null ? { repoRoot: repository.repoRoot } : {}),
        status
      });
      await syncCompanionWatcherRepository(repository.repoIdentityHash, repository.displayLabel, status, repository.repoRoot ?? undefined);
      await refreshLocalRepositories(session);
    } catch (error) {
      setOnboardingError(error instanceof Error ? error.message : "Repository status update failed");
    }
  }

  async function confirmGuiCorrelation(bundle: SourceBundleSummaryResponse) {
    if (session === null) return;
    setEvidenceActionMessage("");
    try {
      await updateEvidenceAttribution(session.token, bundle.id, {
        userAttribution: "manual",
        attributionConfidence: bundle.attributionConfidence ?? undefined,
        attributionReasons: ["human_review"]
      });
      setEvidenceActionMessage("GUI correlation confirmed. Generation remains blocked until direct AI output or patch data is attached.");
      await refreshEvidence(session);
      await selectEvidence(bundle.id);
    } catch (error) {
      setEvidenceActionMessage(error instanceof Error ? error.message : "Evidence confirmation failed");
    }
  }

  async function applyEvidenceAttribution(bundle: SourceBundleSummaryResponse, userAttribution: "use_for_generation" | "manual") {
    if (session === null) return;
    setEvidenceActionMessage("");
    try {
      await updateEvidenceAttribution(session.token, bundle.id, {
        userAttribution,
        attributionConfidence: userAttribution === "use_for_generation" ? 0.9 : undefined,
        attributionReasons: [userAttribution === "use_for_generation" ? "curation_approved" : "human_review"]
      });
      setEvidenceActionMessage("Evidence curation updated.");
      await refreshEvidence(session);
      await selectEvidence(bundle.id);
    } catch (error) {
      setEvidenceActionMessage(error instanceof Error ? error.message : "Evidence curation failed");
    }
  }

  async function removeEvidence(bundle: SourceBundleSummaryResponse) {
    if (session === null) return;
    if (!window.confirm(`Delete "${bundle.title}" from collected evidence?`)) return;
    setEvidenceActionMessage("");
    try {
      await deleteEvidence(session.token, bundle.id);
      setEvidenceActionMessage("Evidence deleted.");
      setSelectedEvidenceId(null);
      setSelectedEvidenceDetail(null);
      await refreshEvidence(session);
    } catch (error) {
      setEvidenceActionMessage(error instanceof Error ? error.message : "Evidence delete failed");
    }
  }

  async function purgeEvidence(bundle: SourceBundleSummaryResponse) {
    if (session === null) return;
    if (!window.confirm(`Purge raw evidence for "${bundle.title}"?`)) return;
    setEvidenceActionMessage("");
    try {
      const result = await purgeEvidenceRaw(session.token, bundle.id);
      setEvidenceActionMessage(`Purged ${result.purgedItems} raw items.`);
      await refreshEvidence(session);
      await selectEvidence(bundle.id);
    } catch (error) {
      setEvidenceActionMessage(error instanceof Error ? error.message : "Raw purge failed");
    }
  }

  async function saveRetentionSettings() {
    if (session === null || membership === null) return;
    setRetentionLoading(true);
    setRetentionMessage("");
    try {
      const settings = await updateEvidenceRetentionSettings(session.token, {
        organizationId: membership.organizationId,
        retentionMode,
        retentionDays: retentionMode === "default" ? normalizeRetentionDays(retentionDays) : null
      });
      setRetentionSettings(settings);
      setRetentionMode(settings.retentionMode);
      setRetentionDays(retentionDaysFormValue(settings));
      setRetentionMessage("Retention policy saved.");
    } catch (error) {
      setRetentionMessage(error instanceof Error ? error.message : "Retention policy update failed");
    } finally {
      setRetentionLoading(false);
    }
  }

  async function purgeAllRawEvidenceNow() {
    if (session === null || membership === null) return;
    if (!window.confirm("Purge all raw evidence now? Metadata, generated cards, and practice progress remain.")) return;
    setRetentionLoading(true);
    setRetentionMessage("");
    try {
      const result = await purgeEvidenceRawScope(session.token, {
        organizationId: membership.organizationId,
        purgeAll: true
      });
      setRetentionMessage(`Purged ${result.purgedItems} raw items. Metadata, generated cards, and practice progress remain.`);
      await refreshEvidence(session);
    } catch (error) {
      setRetentionMessage(error instanceof Error ? error.message : "Raw purge failed");
    } finally {
      setRetentionLoading(false);
    }
  }

  async function generatePracticeFromEvidence(bundle: SourceBundleSummaryResponse) {
    if (session === null || membership === null) return;
    const providerId = selectedGenerationProvider?.id;
    if (providerId === undefined) {
      setEvidenceActionMessage("Select an active generation provider.");
      return;
    }
    setEvidenceActionMessage("");
    try {
      const result = await generateFromEvidence(session.token, {
        organizationId: membership.organizationId,
        providerConfigId: providerId,
        sourceBundleId: bundle.id,
        visibility: "private"
      });
      setLatestCard(result.patternCard);
      setActivity((items) => [`Generated ${result.patternCard.title}`, ...items].slice(0, 6));
      setEvidenceActionMessage("Practice generated.");
      await refreshLibrary(session);
      await refreshConversionTraces(session);
    } catch (error) {
      if (error instanceof ApiRequestError && error.fields?.failureCode) {
        setEvidenceActionMessage(`${error.message}: ${error.fields.failureCode}`);
      } else {
        setEvidenceActionMessage(error instanceof Error ? error.message : "Generation failed");
      }
    }
  }

  function updateLibraryFilter<Key extends keyof LibraryFilters>(key: Key, value: LibraryFilters[Key]) {
    setLibraryFilters((current) => ({ ...current, [key]: value }));
  }

  function toggleEditorTheme() {
    setEditorTheme((current) => {
      const next = current === "vs-dark" ? "vs" : "vs-dark";
      window.localStorage.setItem(EDITOR_THEME_STORAGE_KEY, next);
      return next;
    });
  }

  function openQuickOpen() {
    setWorkbenchQuery("");
    setWorkbenchOverlay("quick-open");
  }

  function openCommandPalette() {
    setWorkbenchQuery("");
    setWorkbenchOverlay("command-palette");
  }

  function closeWorkbenchOverlay() {
    setWorkbenchOverlay(null);
    setWorkbenchQuery("");
  }

  function renderWorkbenchOverlay(problem: PracticeProblemResponse) {
    const query = workbenchQuery.trim().toLowerCase();
    const matchingFiles = matchingFilesFor(query);
    const commands = matchingCommandsFor(query);
    const isQuickOpen = workbenchOverlay === "quick-open";
    const title = isQuickOpen ? "Quick Open" : "Command Palette";

    function matchingFilesFor(nextQuery: string) {
      return problem.files.filter((file) => file.path.toLowerCase().includes(nextQuery));
    }

    function matchingCommandsFor(nextQuery: string) {
      return allCommands().filter((command) => command.label.toLowerCase().includes(nextQuery) || command.detail.toLowerCase().includes(nextQuery));
    }

    function allCommands() {
      return [
        {
          id: "save",
          label: "Save current draft",
          detail: "Persist files locally and sync when online",
          disabled: editorSnapshotRef.current === null,
          disabledReason: "Editor is still loading",
          run: () => {
            const snapshot = editorSnapshotRef.current;
            if (snapshot === null) return;
            closeWorkbenchOverlay();
            void savePracticeDraft(snapshot());
          }
        },
        {
          id: "quick-open",
          label: "Quick open file",
          detail: "Jump to a file in this practice",
          disabled: problem.files.length === 0,
          disabledReason: "No files are available",
          run: openQuickOpen
        },
        {
          id: "toggle-theme",
          label: editorTheme === "vs-dark" ? "Switch editor to light theme" : "Switch editor to dark theme",
          detail: "Change the Monaco editor theme only",
          disabled: false,
          disabledReason: "",
          run: () => {
            toggleEditorTheme();
            closeWorkbenchOverlay();
          }
        },
        {
          id: "run",
          label: "Run tests",
          detail: "Execute visible tests in the local sandbox",
          disabled: editorSnapshotRef.current === null || practiceSaveStatus.state === "running",
          disabledReason: practiceSaveStatus.state === "running" ? "Run in progress" : "Editor is still loading",
          run: () => {
            const snapshot = editorSnapshotRef.current;
            if (snapshot === null) return;
            closeWorkbenchOverlay();
            void runPracticeSolution(snapshot());
          }
        },
        {
          id: "submit",
          label: "Submit solution",
          detail: "Submit the current editor snapshot",
          disabled: editorSnapshotRef.current === null,
          disabledReason: "Editor is still loading",
          run: () => {
            const snapshot = editorSnapshotRef.current;
            if (snapshot === null) return;
            closeWorkbenchOverlay();
            void submitPracticeSolution(snapshot());
          }
        },
        {
          id: "toggle-diff",
          label: diffVisible ? "Hide failed diff" : "Show failed diff",
          detail: problem.latestRun?.failedDiff == null ? "No diff available yet" : "Toggle the latest failed diff",
          disabled: problem.latestRun?.failedDiff == null,
          disabledReason: "No diff available yet",
          run: () => {
            togglePracticeDiff();
            closeWorkbenchOverlay();
          }
        }
      ];
    }

    return (
      <div className="workbench-overlay-backdrop">
        <div className="workbench-overlay" role="dialog" aria-modal="true" aria-label={title}>
          <div className="overlay-header">
            <strong>{title}</strong>
            <button aria-label="Close overlay" onClick={closeWorkbenchOverlay} type="button">
              <X aria-hidden="true" size={16} />
            </button>
          </div>
          <input
            autoFocus
            onChange={(event) => setWorkbenchQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") closeWorkbenchOverlay();
              if (event.key !== "Enter") return;
              const currentQuery = event.currentTarget.value.trim().toLowerCase();
              if (isQuickOpen) {
                const file = matchingFilesFor(currentQuery)[0];
                if (file !== undefined) {
                  setActivePracticePath(file.path);
                  closeWorkbenchOverlay();
                }
                return;
              }
              const command = matchingCommandsFor(currentQuery).find((candidate) => !candidate.disabled);
              command?.run();
            }}
            placeholder={isQuickOpen ? "Search files" : "Search commands"}
            type="search"
            value={workbenchQuery}
          />
          <div className="overlay-list">
            {isQuickOpen
              ? matchingFiles.map((file) => (
                  <button
                    className="overlay-row"
                    key={file.path}
                    onClick={() => {
                      setActivePracticePath(file.path);
                      closeWorkbenchOverlay();
                    }}
                    type="button"
                  >
                    <span>{file.path}</span>
                    <small>{file.readOnly ? "Read-only" : file.language}</small>
                  </button>
                ))
              : commands.map((command) => (
                  <button className="overlay-row" disabled={command.disabled} key={command.id} onClick={command.run} type="button">
                    <span>{command.label}</span>
                    <small>{command.disabled ? command.disabledReason : command.detail}</small>
                  </button>
                ))}
            {(isQuickOpen ? matchingFiles.length : commands.length) === 0 ? <p className="overlay-empty">No matches</p> : null}
          </div>
        </div>
      </div>
    );
  }

  async function savePracticeDraft(files: PracticeAttemptFileRequest[]) {
    if (session === null || activePractice === null) return;

    const draft = ensurePracticeDraft({
      userId: session.user.id,
      problemId: activePractice.id,
      assetRevision: activePractice.assetRevision,
      files
    });
    const queued = enqueuePracticeSync(draft);
    setPracticeSaveStatus({ state: "local_only", message: "Saved locally" });

    if (!window.navigator.onLine) return;

    const syncing = markPracticeSyncing(queued);
    setPracticeSaveStatus({ state: "syncing", message: "Syncing" });
    try {
      await syncLocalPracticeAttempt(session.token, activePractice.id, syncing.request);
      markPracticeSynced(syncing);
      setPracticeSaveStatus({ state: "synced", message: "Synced" });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Sync failed";
      if (isConflictError(error)) {
        markPracticeConflict(syncing, message);
        setPracticeSaveStatus({ state: "conflict", message: "Sync conflict" });
        return;
      }
      markPracticeFailed(syncing, message);
      setPracticeSaveStatus({ state: "failed", message: "Sync failed" });
    }
  }

  function showEditorStatus(message: string) {
    setPracticeSaveStatus({ state: "idle", message });
  }

  function togglePracticeDiff() {
    if (activePractice?.latestRun?.failedDiff == null) {
      setPracticeSaveStatus({ state: "idle", message: "No diff available" });
      return;
    }
    setDiffVisible((current) => {
      const next = !current;
      setPracticeSaveStatus({ state: "idle", message: next ? "Diff shown" : "Diff hidden" });
      return next;
    });
  }

  function isHintRevealed(hint: PracticeHintResponse): boolean {
    return hint.revealed || revealedHintIds.includes(hint.id);
  }

  function canRevealHint(hint: PracticeHintResponse): boolean {
    return hint.revealPolicy === "manual" && hint.content !== null;
  }

  function revealHint(hint: PracticeHintResponse) {
    if (session === null || activePractice === null || !canRevealHint(hint)) {
      setPracticeSaveStatus({ state: "idle", message: "Hint locked" });
      return;
    }
    const scope = { userId: session.user.id, problemId: activePractice.id };
    setRevealedHintIds(saveRevealedHintId(scope, hint.id));
    logHintReveal(scope, hint);
    setPracticeSaveStatus({ state: "idle", message: "Hint revealed" });
  }

  function renderFeedbackPanel(problem: PracticeProblemResponse) {
    const run = problem.latestRun;
    const nextRecommendations = recommendationCards.filter((card) => card.id !== problem.patternCardId).slice(0, 3);
    const runnerLanguageId = primaryPracticeLanguage(problem);
    const runnerLanguage = runnerLanguages.find((language) => language.language === runnerLanguageId) ?? null;
    const status =
      practiceSaveStatus.state === "submitted"
        ? "submitted"
        : practiceSaveStatus.state === "running"
          ? "running"
          : run?.status ?? "not run";
    let summary = "No run yet.";
    if (status === "submitted") {
      summary = "Submission received.";
    } else if (status === "running") {
      summary = "Run in progress.";
    } else if (status === "passed") {
      summary = "Latest run passed.";
    } else if (status === "failed") {
      summary = "Latest run failed.";
    } else if (status === "compile_error") {
      summary = "Compilation failed.";
    } else if (status === "timeout") {
      summary = "Latest run timed out.";
    } else if (status === "resource_limited") {
      summary = "Latest run exceeded sandbox limits.";
    } else if (status === "runner_unavailable") {
      summary = "Local runner is unavailable.";
    }

    return (
      <div className="feedback-panel">
        <strong>Feedback</strong>
        <div className="feedback-section">
          <span>Summary</span>
          <small>{summary}</small>
        </div>
        <div className="feedback-section">
          <span>Tests</span>
          {run?.tests.length ? (
            run.tests.map((test) => (
              <small key={test.name}>
                {test.name}: {test.status}{test.durationMs === null ? "" : ` (${test.durationMs}ms)`}{test.message === null ? "" : ` - ${test.message}`}
              </small>
            ))
          ) : (
            <small>No test results yet.</small>
          )}
        </div>
        <div className="feedback-section">
          <span>Diff</span>
          {run?.failedDiff ? <pre className="feedback-output">{run.failedDiff}</pre> : <small>No failed diff yet.</small>}
        </div>
        <div className="feedback-section">
          <span>Explanation</span>
          <small>{run?.failureReason ?? "Feedback explanation will appear after a run."}</small>
          {run?.stdoutExcerpt ? <pre className="feedback-output">stdout: {run.stdoutExcerpt}</pre> : null}
          {run?.stderrExcerpt ? <pre className="feedback-output">stderr: {run.stderrExcerpt}</pre> : null}
          {status === "runner_unavailable" ? (
            <div className="runner-install-callout">
              <small>{runnerLanguage?.displayName ?? runnerLanguageId} runner is not installed locally.</small>
              <div className="mini-action-row">
                <button
                  disabled={runnerPendingLanguages.has(runnerLanguageId) || runnerLanguage?.status === "installing"}
                  onClick={() => void installRunner(runnerLanguageId)}
                  type="button"
                >
                  <Play aria-hidden="true" size={14} />
                  {runnerPendingLanguages.has(runnerLanguageId) || runnerLanguage?.status === "installing" ? "Installing" : "Install runner"}
                </button>
                <button onClick={() => setActivePage("runners")} type="button">
                  <RefreshCw aria-hidden="true" size={14} />
                  Runners
                </button>
              </div>
            </div>
          ) : null}
        </div>
        <div className="feedback-section">
          <span>Pattern feedback</span>
          <small>Pattern feedback will appear after curation.</small>
        </div>
        <div className="feedback-section">
          <span>Recommendations</span>
          {status === "failed" || status === "compile_error" ? (
            <small>Inspect the runner output and try again.</small>
          ) : nextRecommendations.length > 0 ? (
            nextRecommendations.map((card) => <small key={card.id}>{card.title}</small>)
          ) : (
            <small>Run or submit to receive recommendations.</small>
          )}
        </div>
      </div>
    );
  }

  function exerciseStateLabel(trace: ConversionTraceResponse): string {
    if (trace.status === "failed") return trace.failureCode ?? "Generation failed";
    if (trace.exercise === null) return "Not generated";
    if (trace.exercise.publicationStatus === "published") return "Published";
    return reviewStatusLabel(trace.exercise.reviewStatus);
  }

  function traceBadgeClass(trace: ConversionTraceResponse): string {
    if (trace.status === "failed") return "status-danger";
    if (trace.exercise === null) return "status-warning";
    return "status-success";
  }

  function reviewStatusLabel(status: string | null): string {
    if (status === "open") return "Curation open";
    if (status === "approved") return "Approved";
    if (status === "changes_requested") return "Changes requested";
    if (status === "rejected") return "Rejected";
    return "Draft";
  }

  async function runPracticeSolution(files: PracticeAttemptFileRequest[]) {
    if (session === null || activePractice === null) return;

    const draft = ensurePracticeDraft({
      userId: session.user.id,
      problemId: activePractice.id,
      assetRevision: activePractice.assetRevision,
      files
    });
    setAnswerDiff(null);
    setPracticeSaveStatus({ state: "running", message: "Running tests" });
    try {
      const run = await runPracticeAttempt(session.token, activePractice.id, {
        clientAttemptId: draft.clientAttemptId,
        assetRevision: draft.assetRevision,
        language: primaryPracticeLanguage(activePractice),
        timeoutMs: 5_000,
        files: draft.files.map((file) => ({ path: file.path, content: file.content }))
      });
      setActivePractice((current) => (current?.id === activePractice.id ? { ...current, latestRun: run } : current));
      setPracticeSaveStatus({ state: "idle", message: runStatusMessage(run) });
      if (run.status === "runner_unavailable") {
        void loadRunnerLanguages(session, true);
      }
    } catch {
      setPracticeSaveStatus({ state: "failed", message: "Run failed" });
    }
  }

  async function submitPracticeSolution(files: PracticeAttemptFileRequest[]) {
    if (session === null || activePractice === null) return;

    const draft = ensurePracticeDraft({
      userId: session.user.id,
      problemId: activePractice.id,
      assetRevision: activePractice.assetRevision,
      files
    });
    setAnswerDiff(null);
    setPracticeSaveStatus({ state: "submitting", message: "Submitting" });
    try {
      const response = await submitPracticeAttempt(session.token, activePractice.id, {
        clientAttemptId: draft.clientAttemptId,
        assetRevision: draft.assetRevision,
        language: primaryPracticeLanguage(activePractice),
        files: draft.files.map((file) => ({ path: file.path, content: file.content })),
        resultStatus: "submitted"
      });
      const submittedProblem = response.patternCard.problems.find((problem) => problem.id === activePractice.id);
      const referenceAnswer = submittedProblem?.referenceAnswer;
      const answerFile =
        draft.files.find((file) => activePractice.files.some((source) => source.path === file.path && !source.readOnly)) ?? draft.files[0];
      if (referenceAnswer != null && answerFile !== undefined) {
        const sourceFile = activePractice.files.find((file) => file.path === answerFile.path);
        setAnswerDiff({
          language: sourceFile?.language ?? activePractice.files[0]?.language ?? "plaintext",
          path: answerFile.path,
          referenceAnswer,
          submittedAnswer: answerFile.content
        });
      } else {
        setAnswerDiff(null);
      }
      setPracticeSaveStatus({ state: "submitted", message: "Submitted" });
      void refreshLearningSignals(session);
      void refreshLibrary(session);
    } catch {
      setPracticeSaveStatus({ state: "failed", message: "Submit failed" });
    }
  }

  function runStatusMessage(run: PracticeRunResultResponse): string {
    if (run.status === "passed") return "Run passed";
    if (run.status === "compile_error") return "Compile failed";
    if (run.status === "timeout") return "Run timed out";
    if (run.status === "resource_limited") return "Sandbox limit hit";
    if (run.status === "runner_unavailable") return "Runner unavailable";
    return "Run failed";
  }

  async function openPractice(problemId: string) {
    if (session === null) return;
    setPracticeLoading(true);
    setPracticeError("");
    try {
      const problem = await getPracticeProblem(session.token, problemId);
      setActivePractice(problem);
      setSelectedPracticeCardId(problem.patternCardId);
      setActivePage("practice");
      setActivePracticePath(problem.files[0]?.path ?? null);
      setRevealedHintIds(loadRevealedHintIds({ userId: session.user.id, problemId: problem.id }));
      setProvenanceExpanded(true);
      editorSnapshotRef.current = null;
      closeWorkbenchOverlay();
      setDiffVisible(false);
      setAnswerDiff(null);
      setPracticeSaveStatus({ state: "idle", message: "Not saved" });
      window.setTimeout(() => document.getElementById("practice")?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
    } catch (error) {
      setActivePractice(null);
      setActivePracticePath(null);
      setRevealedHintIds([]);
      setProvenanceExpanded(true);
      editorSnapshotRef.current = null;
      closeWorkbenchOverlay();
      setDiffVisible(false);
      setAnswerDiff(null);
      setPracticeSaveStatus({ state: "idle", message: "Not saved" });
      setPracticeError(error instanceof Error ? error.message : "Practice problem failed to load");
    } finally {
      setPracticeLoading(false);
    }
  }

  function logout() {
    storeSession(null);
    setSession(null);
    setProviders([]);
    setLatestCard(null);
    setLibraryCards([]);
    setProgressScores([]);
    setRecommendationCards([]);
    setConversionTraces([]);
    setConversionTraceError("");
    setLibraryError("");
    setEvidenceList({ bundles: [], page: 0, pageSize: 50, total: 0 });
    setEvidenceError("");
    setSelectedEvidenceId(null);
    setSelectedEvidenceDetail(null);
    setEvidenceActionMessage("");
    setLocalRepositories([]);
    setActivePractice(null);
    setActivePracticePath(null);
    setRevealedHintIds([]);
    setProvenanceExpanded(true);
    editorSnapshotRef.current = null;
    closeWorkbenchOverlay();
    setDiffVisible(false);
    setAnswerDiff(null);
    setPracticeSaveStatus({ state: "idle", message: "Not saved" });
    setPracticeError("");
    setActivity([]);
    setActivePage("dashboard");
    evidenceDetailRequestRef.current += 1;
  }

  async function saveLocalAiSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (session === null || membership === null) return;

    const wasFirstLocalSetup = localAiSettings === null && window.localStorage.getItem(runnerOnboardingStorageKey(session.user.id)) !== "done";
    const apiKey = localApiKey.trim();
    const model = localProviderModel.trim();
    const baseUrl = localProviderBaseUrl.trim();
    const label = oauthLabel.trim();
    if (selectedAuthMethod === "api_key" && apiKey.length === 0) {
      setOnboardingError("API key is required");
      return;
    }
    if (selectedAuthMethod === "api_key" && model.length === 0) {
      setOnboardingError("Model is required");
      return;
    }

    setOnboardingSaving(true);
    setOnboardingError("");
    try {
      let providerConfigId: string | undefined;
      if (selectedAuthMethod === "api_key") {
        const provider = await createProvider(session.token, {
          organizationId: membership.organizationId,
          provider: selectedProvider.backendProvider,
          model,
          baseUrl: baseUrl.length > 0 ? baseUrl : null,
          scope: "personal",
          credential: apiKey,
          retentionMode: "standard",
          authType: "byok"
        });
        providerConfigId = provider.id;
        setSelectedGenerationProviderId(provider.id);
        setProviders(await listProviders(session.token, membership.organizationId));
      }

      const nextSettings: LocalAiSettings = {
        provider: selectedAiProvider,
        authMethod: selectedAuthMethod,
        credentialLabel:
          selectedAuthMethod === "oauth"
            ? label || defaultOauthLabel(selectedAiProvider)
            : `${selectedProvider.label} API key · ${model}`,
        providerConfigId,
        model: selectedAuthMethod === "api_key" ? model : undefined,
        baseUrl: selectedAuthMethod === "api_key" ? baseUrl || null : null,
        configuredAt: new Date().toISOString()
      };

      window.localStorage.setItem(localAiStorageKey(session.user.id), JSON.stringify(nextSettings));
      window.localStorage.removeItem(legacyLocalAiStorageKey(session.user.id));
      setLocalAiSettings(nextSettings);
      setLocalApiKey("");
      if (wasFirstLocalSetup) {
        window.localStorage.setItem(runnerOnboardingStorageKey(session.user.id), "done");
        setActivePage("runners");
      } else {
        goToDashboard();
      }
    } catch (error) {
      setLocalApiKey("");
      setOnboardingError(error instanceof Error ? error.message : "Local setup failed");
    } finally {
      setOnboardingSaving(false);
    }
  }

  function openLocalAiSetup() {
    setActivePage("aiSetup");
  }

  function goToDashboard() {
    setOnboardingError("");
    setActivePage("dashboard");
  }

  async function startOAuthConnection() {
    if (!selectedProvider.oauth) return;
    setOnboardingError("");
    setOauthConnection({ status: "starting", message: "Opening local OAuth connection." });

    try {
      const localToken = await readLocalCompanionToken();
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/oauth/start`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-learnloop-local-token": localToken },
        body: JSON.stringify({ provider: selectedAiProvider })
      });
      const payload = await readCompanionResponse(response);
      if (!response.ok && payload.status !== "running") {
        throw new Error(payload.message || "Local OAuth companion failed to start");
      }
      applyOAuthConnectionPayload(payload, selectedAiProvider);
      if (payload.status !== "connected" && payload.status !== "failed") {
        await pollOAuthConnection(selectedAiProvider);
      }
    } catch (error) {
      setOauthConnection({
        status: "unavailable",
        message:
          error instanceof TypeError
            ? "Local OAuth companion is not running. Start ./scripts/local-ai-companion.sh and try again."
            : error instanceof Error
              ? error.message
              : "Local OAuth connection failed."
      });
    }
  }

  async function pollOAuthConnection(provider: LocalAiProvider) {
    for (let attempt = 0; attempt < 120; attempt += 1) {
      await delay(1500);
      const response = await fetch(`${LOCAL_AI_COMPANION_URL}/oauth/status?provider=${encodeURIComponent(provider)}`);
      const payload = await readCompanionResponse(response);
      applyOAuthConnectionPayload(payload, provider);
      if (payload.status === "connected" || payload.status === "failed" || payload.status === "missing") return;
    }
    setOauthConnection({ status: "failed", message: "OAuth connection did not finish in time." });
  }

  function applyOAuthConnectionPayload(payload: CompanionOAuthResponse, provider: LocalAiProvider) {
    const credentialLabel = payload.credentialLabel || defaultOauthLabel(provider);
    if (payload.status === "connected") {
      setOauthLabel(credentialLabel);
      setOauthConnection({ status: "connected", message: payload.message || `${credentialLabel} connected.` });
    } else if (payload.status === "running" || payload.status === "starting") {
      setOauthLabel(credentialLabel);
      setOauthConnection({ status: "running", message: payload.message || "Complete the OAuth prompt opened by the local companion." });
    } else if (payload.status === "missing") {
      setOauthConnection({ status: "failed", message: payload.message || `${oauthCommand(provider)} is not available on this machine.` });
    } else if (payload.status === "failed") {
      setOauthConnection({ status: "failed", message: payload.message || "OAuth connection failed." });
    }
  }

  async function runDemo() {
    if (session === null || membership === null) return;
    if (localAiSettings === null) {
      openLocalAiSetup();
      return;
    }

    setIsRunning(true);
    setActivity(["Creating evidence"]);
    try {
      const result = await runLearningDemo(session.token, membership);
      setProviders(result.providers);
      setLatestCard(result.patternCard);
      setActivity([
        `Code ${result.codeBundle.status}`,
        `Conversation ${result.conversationBundle.status}`,
        `Link ${result.sourceLink.status}`,
        `Curation ${result.reviewTask.status}`,
        `Card ${result.patternCard.publicationStatus}`
      ]);
      void refreshConversionTraces(session);
      void refreshEvidence(session);
    } catch (error) {
      setActivity([error instanceof Error ? error.message : "Flow failed"]);
    } finally {
      setIsRunning(false);
    }
  }
}
