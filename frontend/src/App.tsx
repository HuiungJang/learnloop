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
  GitPullRequest,
  KeyRound,
  Library,
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
  deleteEvidence,
  generateFromEvidence,
  getConversionTraces,
  createSession,
  fetchHealth,
  getEvidenceDetail,
  getLibrary,
  getPracticeProblem,
  getProgress,
  getRecommendations,
  listEvidence,
  listLocalRepositories,
  type HealthResponse,
  isConflictError,
  listProviders,
  type PracticeAttemptFileRequest,
  purgeEvidenceRaw,
  runPracticeAttempt,
  runLearningDemo,
  submitPracticeAttempt,
  syncLocalPracticeAttempt,
  type EvidenceDetailResponse,
  type EvidenceItemResponse,
  type EvidenceListResponse,
  type ConversionTraceResponse,
  type Membership,
  type PatternCardResponse,
  type PracticeHintResponse,
  type PracticeProblemResponse,
  type PracticeRunResultResponse,
  type ProficiencyResponse,
  type ProviderResponse,
  type SessionResponse,
  type SourceBundleSummaryResponse,
  updateEvidenceAttribution,
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
type RepositoryConsentStatus = "approved" | "revoked" | "always_ignored" | "missing";
type LocalRepositoryConsent = {
  repoIdentityHash: string;
  displayLabel: string;
  status: RepositoryConsentStatus;
  updatedAt: string;
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

const aiProviders: Array<{ id: LocalAiProvider; label: string; icon: LucideIcon; oauth: boolean }> = [
  { id: "codex", label: "Codex", icon: Bot, oauth: true },
  { id: "gemini", label: "Gemini", icon: Sparkles, oauth: true },
  { id: "claude", label: "Claude", icon: Cloud, oauth: false }
];

const LOCAL_AI_STORAGE_PREFIX = "learnloop:local-ai:";
const LEGACY_LOCAL_AI_STORAGE_PREFIX = "ai-code-learning:local-ai:";
const SESSION_STORAGE_KEY = "learnloop:session";
const EDITOR_THEME_STORAGE_KEY = "learnloop:editor-theme";
const LOCAL_AI_COMPANION_URL = import.meta.env.VITE_LOCAL_AI_COMPANION_URL ?? "http://127.0.0.1:4317";
const LOCAL_AI_SETUP_HISTORY_VIEW = "local-ai-setup";
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

function readLocalAiSettings(userId: string): LocalAiSettings | null {
  const key = localAiStorageKey(userId);
  const legacyKey = legacyLocalAiStorageKey(userId);
  const raw = window.localStorage.getItem(key) ?? window.localStorage.getItem(legacyKey);
  if (raw === null) return null;

  try {
    const settings = JSON.parse(raw) as LocalAiSettings;
    if (window.localStorage.getItem(key) === null) {
      window.localStorage.setItem(key, raw);
      window.localStorage.removeItem(legacyKey);
    }
    return settings;
  } catch {
    window.localStorage.removeItem(key);
    window.localStorage.removeItem(legacyKey);
    return null;
  }
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

function bundleCurationLabel(bundle: SourceBundleSummaryResponse): string {
  if (bundle.userAttribution === "use_for_generation") return "Use";
  if (bundle.userAttribution === "manual") return "Manual";
  if (bundle.status === "quarantined_secret") return "Quarantined";
  if (bundle.status === "purged_raw") return "Purged";
  return "Uncurated";
}

function bundleStatusClass(bundle: SourceBundleSummaryResponse): string {
  if (bundle.status === "quarantined_secret") return "status-danger";
  if (bundle.userAttribution === "use_for_generation") return "status-success";
  return "status-neutral";
}

function attributionLabel(value: string): string {
  if (value === "ai_assisted") return "AI assisted";
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

function formatShortDate(value: string): string {
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function formatBytes(value: number | null): string {
  if (value === null) return "size unknown";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function toLocalRepositoryConsent(repository: LocalRepositoryConsentResponse): LocalRepositoryConsent {
  return {
    repoIdentityHash: repository.repoIdentityHash,
    displayLabel: repository.displayLabel,
    status: repository.status,
    updatedAt: repository.updatedAt
  };
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
    return nextSession as SessionResponse;
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

function isLocalAiSetupHistoryState(state: unknown) {
  return typeof state === "object" && state !== null && "learnloopView" in state && state.learnloopView === LOCAL_AI_SETUP_HISTORY_VIEW;
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
  const [localRepositories, setLocalRepositories] = useState<LocalRepositoryConsent[]>([]);
  const [repositoryLabel, setRepositoryLabel] = useState("");
  const [showOnboarding, setShowOnboarding] = useState(false);
  const [selectedAiProvider, setSelectedAiProvider] = useState<LocalAiProvider>("codex");
  const [selectedAuthMethod, setSelectedAuthMethod] = useState<LocalAuthMethod>("api_key");
  const [editorTheme, setEditorTheme] = useState<EditorTheme>(() => readEditorTheme());
  const [localApiKey, setLocalApiKey] = useState("");
  const [oauthLabel, setOauthLabel] = useState("");
  const [oauthConnection, setOauthConnection] = useState<LocalOAuthConnection>({ status: "idle", message: "" });
  const [onboardingError, setOnboardingError] = useState("");
  const [health, setHealth] = useState<HealthState>({ status: "pending" });
  const showOnboardingRef = useRef(showOnboarding);
  const sessionRef = useRef(session);
  const restoredSessionRef = useRef(session !== null);
  const evidenceDetailRequestRef = useRef(0);

  const membership = useMemo(() => primaryMembership(session), [session]);
  const selectedProvider = aiProviders.find((provider) => provider.id === selectedAiProvider) ?? aiProviders[0];

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
    setOauthConnection({ status: "idle", message: "" });
  }, [selectedAiProvider, selectedAuthMethod]);

  useEffect(() => {
    showOnboardingRef.current = showOnboarding;
  }, [showOnboarding]);

  useEffect(() => {
    sessionRef.current = session;
    storeSession(session);
    if (session === null) {
      setLocalRepositories([]);
    } else {
      void refreshLocalRepositories(session);
    }
  }, [session]);

  useEffect(() => {
    const handlePopState = (event: PopStateEvent) => {
      if (sessionRef.current !== null && isLocalAiSetupHistoryState(event.state)) {
        setShowOnboarding(true);
        return;
      }
      if (showOnboardingRef.current) {
        setShowOnboarding(false);
      }
    };

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (!restoredSessionRef.current || session === null) return;
    restoredSessionRef.current = false;

    const storedSettings = readLocalAiSettings(session.user.id);
    setLocalAiSettings(storedSettings);
    if (storedSettings === null) {
      openLocalAiSetup();
    } else {
      setShowOnboarding(false);
    }
    refreshProviders(session).catch(() => logout());
  }, [session]);

  useEffect(() => {
    if (session === null || membership === null || showOnboarding) return;
    void refreshLibrary(session);
  }, [libraryFilters, membership, session, showOnboarding]);

  useEffect(() => {
    if (session === null || membership === null || showOnboarding) return;
    void refreshLearningSignals(session);
    void refreshConversionTraces(session);
    void refreshEvidence(session);
  }, [membership, session, showOnboarding]);

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

        <button className="local-ai-card" onClick={openLocalAiSetup} type="button">
          <span className="icon-pill">
            <KeyRound aria-hidden="true" size={16} />
          </span>
          <span>
            <strong>{localAiSettings?.provider ?? "AI setup"}</strong>
            <small>{localAiSettings ? localAiSettings.authMethod.replace("_", " ") : "Required"}</small>
          </span>
        </button>

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
        {showOnboarding ? (
          <section className="onboarding-panel">
            <div className="onboarding-header">
              <div>
                <p className="eyebrow">Local AI setup</p>
                <h1>Choose your coding assistant.</h1>
              </div>
              <button className="secondary-button" onClick={goToDashboard} type="button">
                <ChevronLeft aria-hidden="true" size={16} />
                Back to dashboard
              </button>
            </div>
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
                <label>
                  <span>API key</span>
                  <input autoComplete="off" onChange={(event) => setLocalApiKey(event.target.value)} type="password" value={localApiKey} />
                </label>
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
                <span>Stored only in this browser.</span>
              </div>

              {selectedAuthMethod === "oauth" ? (
                <div className="oauth-command">
                  <span>{oauthCommand(selectedAiProvider)}</span>
                </div>
              ) : null}

              {onboardingError.length > 0 ? <p className="form-error">{onboardingError}</p> : null}
              <button className="primary-button" type="submit">
                <CheckCircle2 aria-hidden="true" size={16} />
                Save local setup
              </button>
            </form>
            {renderRepositorySettings()}
          </section>
        ) : (
          <>
            <header className="topbar">
              <div>
                <p className="eyebrow">AI-assisted code learning workspace</p>
                <h1>Generated code becomes curated practice.</h1>
              </div>
              <div className={`health-pill ${health.status}`}>
                <CheckCircle2 aria-hidden="true" size={16} />
                <span>{healthLabel}</span>
              </div>
            </header>

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

            <section className="workbench" id="evidence">
              <div className="panel panel-wide">
                <div className="panel-title">
                  <GitPullRequest aria-hidden="true" size={20} />
                  <h2>Workflow Run</h2>
                </div>
                <div className="action-row">
                  <button className="secondary-button" onClick={() => refreshProviders(session)} type="button">
                    <RefreshCw aria-hidden="true" size={16} />
                    {session.user.displayName}
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

              {renderCollectedEvidencePanel()}

              <div className="panel panel-wide">
                <div className="panel-title">
                  <GitPullRequest aria-hidden="true" size={20} />
                  <h2>Conversion Trace</h2>
                </div>
                {conversionTraceError.length > 0 ? <p className="form-error">{conversionTraceError}</p> : null}
                <div className="trace-list">
                  {conversionTraces.length === 0 && conversionTraceError.length === 0 ? <p className="muted-copy">No conversion traces yet.</p> : null}
                  {conversionTraces.map((trace) => (
                    <div className="trace-row" key={trace.generationRunId}>
                      <div className="trace-cell">
                        <strong>Source</strong>
                        <span>{trace.source?.codeTitle ?? "Unlinked source"}</span>
                        <small>{trace.source?.conversationTitle ?? trace.source?.sourceLinkStatus ?? trace.status}</small>
                      </div>
                      <div className="trace-cell">
                        <strong>Pattern</strong>
                        <span>{trace.pattern?.title ?? "Pattern pending"}</span>
                        <small>{trace.pattern?.tags.map((tag) => tag.name).slice(0, 3).join(", ") || trace.pattern?.summary || "No pattern summary"}</small>
                      </div>
                      <div className="trace-cell">
                        <strong>Exercise</strong>
                        <span>{exerciseStateLabel(trace)}</span>
                        <small>
                          {trace.exercise === null
                            ? "No exercise state"
                            : `${trace.exercise.problemCount} exercises · ${trace.exercise.difficulties.join(", ") || "difficulty pending"}`}
                        </small>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="panel" id="review">
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

                <div className="practice-card-list" aria-live="polite">
                  {libraryLoading ? <p className="muted-copy">Loading practice cards.</p> : null}
                  {libraryError.length > 0 ? <p className="form-error">{libraryError}</p> : null}
                  {!libraryLoading && libraryError.length === 0 && libraryCards.length === 0 ? (
                    <p className="muted-copy">No practice cards match these filters.</p>
                  ) : null}
                  {!libraryLoading && libraryError.length === 0
                    ? libraryCards.map((card) => (
                        <article className="practice-card" key={card.id}>
                          <div className="practice-card-header">
                            <strong>{card.title}</strong>
                            <span>{card.problems[0]?.difficulty ?? "practice"}</span>
                          </div>
                          <p>{card.summary}</p>
                          <div className="tag-row">
                            {card.tags.slice(0, 4).map((tag) => (
                              <span key={`${card.id}:${tag.tagType}:${tag.name}`}>{tag.name}</span>
                            ))}
                          </div>
                          <div className="problem-list">
                            {card.problems.map((problem) => (
                              <span key={problem.id}>{problem.type}</span>
                            ))}
                          </div>
                          <button
                            className="secondary-button practice-open-button"
                            disabled={card.problems[0] === undefined}
                            onClick={() => {
                              const problem = card.problems[0];
                              if (problem !== undefined) void openPractice(problem.id);
                            }}
                            type="button"
                          >
                            <BookOpen aria-hidden="true" size={16} />
                            Open practice
                          </button>
                        </article>
                      ))
                    : null}
                </div>
              </div>
            </section>

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

            <section className="split-grid">
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
        )}
      </main>
    </div>
  );

  function renderRepositorySettings() {
    const counts = repositoryStatusCounts(localRepositories);

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
        <div className="repository-approval-row">
          <input
            onChange={(event) => setRepositoryLabel(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void approveRepository();
              }
            }}
            placeholder="Repository label or path"
            type="text"
            value={repositoryLabel}
          />
          <button className="primary-button" onClick={() => void approveRepository()} type="button">
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

  function renderEvidenceSummary(bundle: SourceBundleSummaryResponse) {
    const canUseForGeneration = bundle.sourceKind === "local_ai_session" && bundle.status === "generation_eligible";
    const canGenerate = canUseForGeneration && bundle.userAttribution === "use_for_generation";

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
          <span>{reasonCodes(bundle.attributionReasonsJson).join(", ") || "no reason codes"}</span>
        </div>
        <div className="mini-action-row">
          <button disabled={!canUseForGeneration} onClick={() => void applyEvidenceAttribution(bundle, "use_for_generation")} type="button">
            <CheckCircle2 aria-hidden="true" size={14} />
            Use
          </button>
          <button onClick={() => void applyEvidenceAttribution(bundle, "manual")} type="button">
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

      setSession(nextSession);
      setPassword("");
      const storedSettings = readLocalAiSettings(nextSession.user.id);
      setLocalAiSettings(storedSettings);
      if (storedSettings === null) {
        openLocalAiSetup();
      } else {
        setShowOnboarding(false);
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
    setProviders(await listProviders(currentSession.token, currentMembership.organizationId));
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
        pageSize: 24
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
      return;
    }

    try {
      const repositories = await listLocalRepositories(currentSession.token, currentMembership.organizationId);
      setLocalRepositories(repositories.map(toLocalRepositoryConsent));
    } catch {
      setLocalRepositories([]);
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
        status: "approved"
      });
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
        status
      });
      await refreshLocalRepositories(session);
    } catch (error) {
      setOnboardingError(error instanceof Error ? error.message : "Repository status update failed");
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

  async function generatePracticeFromEvidence(bundle: SourceBundleSummaryResponse) {
    if (session === null || membership === null) return;
    const providerId = providers.find((provider) => provider.status === "active")?.id ?? "provider-local-mock";
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
      setEvidenceActionMessage(error instanceof Error ? error.message : "Generation failed");
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
    if (trace.exercise === null) return "Not generated";
    if (trace.exercise.publicationStatus === "published") return "Published";
    return reviewStatusLabel(trace.exercise.reviewStatus);
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
        language: activePractice.files[0]?.language ?? "typescript",
        timeoutMs: 5_000,
        files: draft.files.map((file) => ({ path: file.path, content: file.content }))
      });
      setActivePractice((current) => (current?.id === activePractice.id ? { ...current, latestRun: run } : current));
      setPracticeSaveStatus({ state: "idle", message: runStatusMessage(run) });
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
        language: activePractice.files[0]?.language ?? "typescript",
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
      setActivePracticePath(problem.files[0]?.path ?? null);
      setRevealedHintIds(loadRevealedHintIds({ userId: session.user.id, problemId: problem.id }));
      setProvenanceExpanded(true);
      editorSnapshotRef.current = null;
      closeWorkbenchOverlay();
      setDiffVisible(false);
      setAnswerDiff(null);
      setPracticeSaveStatus({ state: "idle", message: "Not saved" });
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
    setShowOnboarding(false);
    evidenceDetailRequestRef.current += 1;
    replaceLocalAiSetupHistoryState();
  }

  function saveLocalAiSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (session === null) return;

    const apiKey = localApiKey.trim();
    const label = oauthLabel.trim();
    if (selectedAuthMethod === "api_key" && apiKey.length === 0) {
      setOnboardingError("API key is required");
      return;
    }

    const nextSettings: LocalAiSettings = {
      provider: selectedAiProvider,
      authMethod: selectedAuthMethod,
      credentialLabel:
        selectedAuthMethod === "oauth"
          ? label || (selectedAiProvider === "codex" ? "Codex CLI OAuth" : "Google OAuth")
          : `${selectedProvider.label} API key`,
      ...(selectedAuthMethod === "api_key" ? { apiKey } : {}),
      configuredAt: new Date().toISOString()
    };

    window.localStorage.setItem(localAiStorageKey(session.user.id), JSON.stringify(nextSettings));
    setLocalAiSettings(nextSettings);
    setLocalApiKey("");
    setOnboardingError("");
    goToDashboard();
  }

  function openLocalAiSetup() {
    if (!isLocalAiSetupHistoryState(window.history.state)) {
      const currentState = typeof window.history.state === "object" && window.history.state !== null ? window.history.state : {};
      window.history.pushState({ ...currentState, learnloopView: LOCAL_AI_SETUP_HISTORY_VIEW }, "", window.location.href);
    }
    setShowOnboarding(true);
  }

  function goToDashboard() {
    setOnboardingError("");
    setShowOnboarding(false);
    replaceLocalAiSetupHistoryState();
  }

  function replaceLocalAiSetupHistoryState() {
    if (!isLocalAiSetupHistoryState(window.history.state)) return;
    const currentState = typeof window.history.state === "object" && window.history.state !== null ? { ...window.history.state } : {};
    delete currentState.learnloopView;
    window.history.replaceState(Object.keys(currentState).length > 0 ? currentState : null, "", window.location.href);
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
      setShowOnboarding(true);
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
