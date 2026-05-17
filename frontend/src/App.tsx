import { useQuery } from "@tanstack/react-query";
import {
  BookOpen,
  CheckCircle2,
  GitPullRequest,
  Library,
  ListChecks,
  ShieldCheck,
  Sparkles,
  UploadCloud,
  Play,
  RefreshCw
} from "lucide-react";
import { createSession, fetchHealth, listProviders, runLearningDemo, type DemoUser, type PatternCardResponse, type ProgressResponse, type ProviderResponse } from "./api/client";
import { useState } from "react";

const demoUsers: DemoUser[] = ["admin", "contributor", "reviewer", "learner"];

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

export function App() {
  const [selectedUser, setSelectedUser] = useState<DemoUser>("contributor");
  const [demoPassword, setDemoPassword] = useState("");
  const [sessionLabel, setSessionLabel] = useState("No session");
  const [providers, setProviders] = useState<ProviderResponse[]>([]);
  const [latestCard, setLatestCard] = useState<PatternCardResponse | null>(null);
  const [progress, setProgress] = useState<ProgressResponse | null>(null);
  const [activity, setActivity] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState(false);

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

  return (
    <div className="app-shell">
      <aside className="sidebar" aria-label="Primary">
        <a className="brand" href="/">
          <span className="brand-mark">AI</span>
          <span>Code Learning</span>
        </a>

        <nav className="nav-list">
          {demoUsers.map((user) => (
            <button className={selectedUser === user ? "nav-active" : ""} key={user} onClick={() => setSelectedUser(user)} type="button">
              {user}
            </button>
          ))}
        </nav>

        <label className="demo-password-field">
          <span>Demo password</span>
          <input
            autoComplete="current-password"
            onChange={(event) => setDemoPassword(event.target.value)}
            placeholder="From .env"
            type="password"
            value={demoPassword}
          />
        </label>

        <div className="sidebar-status">
          <ShieldCheck aria-hidden="true" size={18} />
          <span>Internal org mode</span>
        </div>
      </aside>

      <main className="workspace">
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
              <button className="secondary-button" onClick={() => loginSelected(selectedUser)} type="button">
                <RefreshCw aria-hidden="true" size={16} />
                {sessionLabel}
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
              <p className="muted-copy">No published card yet.</p>
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
              {progress?.proficiency.map((item) => (
                <div className="review-row" key={item.tagName}>
                  <span>{item.tagName}</span>
                  <strong>{item.score}</strong>
                </div>
              )) ?? <p className="muted-copy">No submissions yet.</p>}
            </div>
          </div>
        </section>
      </main>
    </div>
  );

  async function loginSelected(user: DemoUser) {
    const password = demoPassword.trim();
    if (password.length === 0) {
      setActivity(["Enter demo password"]);
      return;
    }

    const session = await createSession(user, password);
    const nextProviders = await listProviders(session.token);
    setSessionLabel(`${session.user.displayName}`);
    setProviders(nextProviders);
  }

  async function runDemo() {
    const password = demoPassword.trim();
    if (password.length === 0) {
      setActivity(["Enter demo password"]);
      return;
    }

    setIsRunning(true);
    setActivity(["Creating evidence"]);
    try {
      const result = await runLearningDemo(password);
      setProviders(result.providers);
      setLatestCard(result.patternCard);
      setProgress(result.progress);
      setActivity([
        `Code ${result.codeBundle.status}`,
        `Conversation ${result.conversationBundle.status}`,
        `Link ${result.sourceLink.status}`,
        `Review ${result.reviewTask.status}`,
        "Submission accepted"
      ]);
    } catch (error) {
      setActivity([error instanceof Error ? error.message : "Flow failed"]);
    } finally {
      setIsRunning(false);
    }
  }
}
