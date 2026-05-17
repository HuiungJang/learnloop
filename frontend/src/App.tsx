import { useQuery } from "@tanstack/react-query";
import {
  BookOpen,
  Bot,
  CheckCircle2,
  Cloud,
  GitPullRequest,
  KeyRound,
  Library,
  ListChecks,
  LockKeyhole,
  LogIn,
  LogOut,
  Play,
  RefreshCw,
  ShieldCheck,
  Sparkles,
  UploadCloud,
  UserPlus,
  type LucideIcon
} from "lucide-react";
import { type FormEvent, useEffect, useMemo, useState } from "react";
import {
  createSession,
  fetchHealth,
  listProviders,
  registerUser,
  runLearningDemo,
  type Membership,
  type PatternCardResponse,
  type ProviderResponse,
  type SessionResponse
} from "./api/client";

type AuthMode = "login" | "register";
type LocalAiProvider = "codex" | "gemini" | "claude";
type LocalAuthMethod = "api_key" | "oauth";

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

function localAiStorageKey(userId: string) {
  return `ai-code-learning:local-ai:${userId}`;
}

function readLocalAiSettings(userId: string): LocalAiSettings | null {
  const raw = window.localStorage.getItem(localAiStorageKey(userId));
  if (raw === null) return null;

  try {
    return JSON.parse(raw) as LocalAiSettings;
  } catch {
    window.localStorage.removeItem(localAiStorageKey(userId));
    return null;
  }
}

function primaryMembership(session: SessionResponse | null): Membership | null {
  return session?.user.memberships[0] ?? null;
}

export function App() {
  const [authMode, setAuthMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [authError, setAuthError] = useState("");
  const [session, setSession] = useState<SessionResponse | null>(null);
  const [providers, setProviders] = useState<ProviderResponse[]>([]);
  const [latestCard, setLatestCard] = useState<PatternCardResponse | null>(null);
  const [activity, setActivity] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);
  const [localAiSettings, setLocalAiSettings] = useState<LocalAiSettings | null>(null);
  const [showOnboarding, setShowOnboarding] = useState(false);
  const [selectedAiProvider, setSelectedAiProvider] = useState<LocalAiProvider>("codex");
  const [selectedAuthMethod, setSelectedAuthMethod] = useState<LocalAuthMethod>("api_key");
  const [localApiKey, setLocalApiKey] = useState("");
  const [oauthLabel, setOauthLabel] = useState("");
  const [onboardingError, setOnboardingError] = useState("");

  const membership = useMemo(() => primaryMembership(session), [session]);
  const selectedProvider = aiProviders.find((provider) => provider.id === selectedAiProvider) ?? aiProviders[0];

  const healthQuery = useQuery({
    queryKey: ["health"],
    queryFn: fetchHealth
  });

  const healthLabel =
    healthQuery.status === "success"
      ? `API ${healthQuery.data.status}`
      : healthQuery.status === "pending"
        ? "API checking"
        : "API offline";

  useEffect(() => {
    if (!selectedProvider.oauth && selectedAuthMethod === "oauth") {
      setSelectedAuthMethod("api_key");
    }
  }, [selectedAuthMethod, selectedProvider.oauth]);

  if (session === null) {
    return (
      <main className="auth-shell">
        <section className="auth-panel">
          <a className="brand" href="/">
            <span className="brand-mark">AI</span>
            <span>Code Learning</span>
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
          <span className="brand-mark">AI</span>
          <span>Code Learning</span>
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
              <div className={`health-pill ${healthQuery.status}`}>
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
                  <h2>Latest Card</h2>
                </div>
                {latestCard ? (
                  <div className="card-detail">
                    <strong>{latestCard.title}</strong>
                    <p>{latestCard.summary}</p>
                    <div className="tag-row">
                      {latestCard.tags.map((tag) => (
                        <span key={`${tag.tagType}:${tag.name}`}>{tag.name}</span>
                      ))}
                    </div>
                    <div className="problem-list">
                      {latestCard.problems.map((problem) => (
                        <span key={problem.id}>{problem.referenceAnswer ? "Answered" : problem.difficulty}</span>
                      ))}
                    </div>
                  </div>
                ) : (
                  <p className="muted-copy">No generated card yet.</p>
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

  function logout() {
    setSession(null);
    setProviders([]);
    setLatestCard(null);
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
