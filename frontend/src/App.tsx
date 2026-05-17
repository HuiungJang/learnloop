import { useQuery } from "@tanstack/react-query";
import {
  BookOpen,
  CheckCircle2,
  GitPullRequest,
  Library,
  ListChecks,
  ShieldCheck,
  Sparkles,
  UploadCloud
} from "lucide-react";
import { fetchHealth } from "./api/client";

const navItems = ["Evidence", "Patterns", "Review", "Learning"];

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

const reviewRows = [
  ["Diff-driven repository layer", "Spring Data JPA", "Needs review"],
  ["OAuth provider registration", "Security", "Draft"],
  ["React Query cache invalidation", "Frontend", "Published"]
];

export function App() {
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
          {navItems.map((item) => (
            <a href={`#${item.toLowerCase()}`} key={item}>
              {item}
            </a>
          ))}
        </nav>

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
              <h2>Source Bundle Queue</h2>
            </div>
            <div className="bundle-list">
              <button type="button">Codex Obsidian Sync</button>
              <button type="button">Pull Request URL</button>
              <button type="button">Commit SHA</button>
              <button type="button">Manual Diff</button>
            </div>
          </div>

          <div className="panel" id="review">
            <div className="panel-title">
              <BookOpen aria-hidden="true" size={20} />
              <h2>Review Queue</h2>
            </div>
            <div className="review-table" role="table" aria-label="Review queue">
              {reviewRows.map(([name, tag, status]) => (
                <div className="review-row" role="row" key={name}>
                  <span>{name}</span>
                  <small>{tag}</small>
                  <strong>{status}</strong>
                </div>
              ))}
            </div>
          </div>
        </section>
      </main>
    </div>
  );
}
