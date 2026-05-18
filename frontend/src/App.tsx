import {
  BookOpen,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Cloud,
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
  UploadCloud,
  UserPlus,
  X,
  type LucideIcon
} from "lucide-react";
import { Suspense, lazy, type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import {
  createSession,
  fetchHealth,
  getLibrary,
  getPracticeProblem,
  type HealthResponse,
  isConflictError,
  listProviders,
  type PracticeAttemptFileRequest,
  registerUser,
  runPracticeAttempt,
  runLearningDemo,
  submitPracticeAttempt,
  syncLocalPracticeAttempt,
  type Membership,
  type PatternCardResponse,
  type PracticeHintResponse,
  type PracticeProblemResponse,
  type PracticeRunResultResponse,
  type ProviderResponse,
  type SessionResponse
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

type AuthMode = "login" | "register";
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

const workflowCards = [
  {
    title: "Evidence Intake",
    description: "Collect Codex, Gemini, Claude, PR, commit, and diff evidence into one reviewable source bundle.",
    icon: UploadCloud,
    metric: "6 sources"
  },
  {
    title: "Pattern Generation",
    description: "Extract design patterns, APIs, libraries, algorithms, and configuration steps from approved evidence.",
    icon: Sparkles,
    metric: "AI assisted"
  },
  {
    title: "Human Review",
    description: "Require reviewer approval before generated cards become reusable organization learning assets.",
    icon: ListChecks,
    metric: "default gate"
  },
  {
    title: "Practice Library",
    description: "Turn reviewed cards into implementation prompts, Q&A, and problem-solving exercises for learners.",
    icon: Library,
    metric: "reusable"
  }
];

const aiProviders: Array<{ id: LocalAiProvider; label: string; icon: LucideIcon; oauth: boolean }> = [
  { id: "codex", label: "Codex", icon: Bot, oauth: true },
  { id: "gemini", label: "Gemini", icon: Sparkles, oauth: true },
  { id: "claude", label: "Claude", icon: Cloud, oauth: false }
];

const LOCAL_AI_STORAGE_PREFIX = "learnloop:local-ai:";
const LEGACY_LOCAL_AI_STORAGE_PREFIX = "ai-code-learning:local-ai:";
const EDITOR_THEME_STORAGE_KEY = "learnloop:editor-theme";
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

function readEditorTheme(): EditorTheme {
  return window.localStorage.getItem(EDITOR_THEME_STORAGE_KEY) === "vs" ? "vs" : "vs-dark";
}

function primaryMembership(session: SessionResponse | null): Membership | null {
  return session?.user.memberships[0] ?? null;
}

export function App() {
  const editorSnapshotRef = useRef<(() => PracticeAttemptFileRequest[]) | null>(null);
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [providers, setProviders] = useState<ProviderResponse[]>([]);
  const [latestCard, setLatestCard] = useState<PatternCardResponse | null>(null);
  const [libraryCards, setLibraryCards] = useState<PatternCardResponse[]>([]);
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
  const [showOnboarding, setShowOnboarding] = useState(false);
  const [selectedAiProvider, setSelectedAiProvider] = useState<LocalAiProvider>("codex");
  const [selectedAuthMethod, setSelectedAuthMethod] = useState<LocalAuthMethod>("api_key");
  const [editorTheme, setEditorTheme] = useState<EditorTheme>(() => readEditorTheme());
  const [localApiKey, setLocalApiKey] = useState("");
  const [oauthLabel, setOauthLabel] = useState("");
  const [onboardingError, setOnboardingError] = useState("");
  const [health, setHealth] = useState<HealthState>({ status: "pending" });

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
    if (session === null || membership === null || showOnboarding) return;
    void refreshLibrary(session);
  }, [libraryFilters, membership, session, showOnboarding]);

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
            <h1>Sign in to your reviewed code practice.</h1>
          </div>

          <div className="auth-tabs" role="tablist" aria-label="Authentication mode">
            <button className={authMode === "login" ? "nav-active" : ""} onClick={() => setAuthMode("login")} type="button">
              <LogIn aria-hidden="true" size={16} />
              <span>Login</span>
            </button>
            <button className={authMode === "register" ? "nav-active" : ""} onClick={() => setAuthMode("register")} type="button">
              <UserPlus aria-hidden="true" size={16} />
              <span>Sign up</span>
            </button>
          </div>

          <form className="auth-form" onSubmit={handleAuthSubmit}>
            {authMode === "register" ? (
              <label>
                <span>Display name</span>
                <input autoComplete="name" onChange={(event) => setDisplayName(event.target.value)} required type="text" value={displayName} />
              </label>
            ) : null}
            <label>
              <span>Email</span>
              <input autoComplete="email" onChange={(event) => setEmail(event.target.value)} required type="email" value={email} />
            </label>
            <label>
              <span>Password</span>
              <input
                autoComplete={authMode === "register" ? "new-password" : "current-password"}
                minLength={authMode === "register" ? 8 : undefined}
                onChange={(event) => setPassword(event.target.value)}
                required
                type="password"
                value={password}
              />
            </label>
            {authError.length > 0 ? <p className="form-error">{authError}</p> : null}
            <button className="primary-button" type="submit">
              {authMode === "register" ? <UserPlus aria-hidden="true" size={16} /> : <LogIn aria-hidden="true" size={16} />}
              {authMode === "register" ? "Create account" : "Login"}
            </button>
          </form>
        </section>

        <section className="auth-preview" aria-label="Platform workflow">
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
        <a className="brand" href="/">
          <span className="brand-mark">LL</span>
          <span>LearnLoop</span>
        </a>

        <div className="account-card">
          <strong>{session.user.displayName}</strong>
          <span>{session.user.email}</span>
          <small>{membership?.role ?? "member"}</small>
        </div>

        <button className="local-ai-card" onClick={() => setShowOnboarding(true)} type="button">
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
          <span>Internal org mode</span>
        </div>
      </aside>

      <main className="workspace">
        {showOnboarding ? (
          <section className="onboarding-panel">
            <div>
              <p className="eyebrow">Local AI setup</p>
              <h1>Choose your coding assistant.</h1>
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
              )}

              <div className="local-only-note">
                <ShieldCheck aria-hidden="true" size={16} />
                <span>Stored only in this browser.</span>
              </div>

              {selectedAuthMethod === "oauth" ? (
                <div className="oauth-command">
                  <span>{selectedAiProvider === "codex" ? "codex --login" : "gcloud auth application-default login"}</span>
                </div>
              ) : null}

              {onboardingError.length > 0 ? <p className="form-error">{onboardingError}</p> : null}
              <button className="primary-button" type="submit">
                <CheckCircle2 aria-hidden="true" size={16} />
                Save local setup
              </button>
            </form>
          </section>
        ) : (
          <>
            <header className="topbar">
              <div>
                <p className="eyebrow">AI-assisted code learning workspace</p>
                <h1>Generated code becomes reviewed practice.</h1>
              </div>
              <div className={`health-pill ${health.status}`}>
                <CheckCircle2 aria-hidden="true" size={16} />
                <span>{healthLabel}</span>
              </div>
            </header>

            <section className="summary-grid" aria-label="Platform workflow">
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
                  <p className="muted-copy">No submissions yet.</p>
                </div>
              </div>
            </section>
          </>
        )}
      </main>
    </div>
  );

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAuthError("");

    try {
      const nextSession =
        authMode === "register"
          ? await registerUser(email, displayName, password)
          : await createSession(email, password);

      setSession(nextSession);
      setPassword("");
      const storedSettings = readLocalAiSettings(nextSession.user.id);
      setLocalAiSettings(storedSettings);
      setShowOnboarding(storedSettings === null);
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
          detail: "Execute visible TypeScript tests in the local sandbox",
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
          <small>Pattern feedback will appear after review.</small>
        </div>
        <div className="feedback-section">
          <span>Recommendations</span>
          <small>{status === "failed" || status === "compile_error" ? "Review the runner output and try again." : "Run or submit to receive recommendations."}</small>
        </div>
      </div>
    );
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
    setSession(null);
    setProviders([]);
    setLatestCard(null);
    setLibraryCards([]);
    setLibraryError("");
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
    setShowOnboarding(false);
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
        `Review ${result.reviewTask.status}`,
        `Card ${result.patternCard.publicationStatus}`
      ]);
    } catch (error) {
      setActivity([error instanceof Error ? error.message : "Flow failed"]);
    } finally {
      setIsRunning(false);
    }
  }
}
