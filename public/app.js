const state = {
  userId: "u-admin",
  sessionToken: null,
  organizationId: "org-demo",
  teamId: "team-platform",
  projectId: "project-learning",
  codeBundleId: null,
  conversationBundleId: null,
  sourceLinkId: null,
  reviewTaskId: null,
  patternCardId: null,
  problemId: null
};

const demoEmails = {
  "u-admin": "admin@example.com",
  "u-contributor": "contributor@example.com",
  "u-reviewer": "reviewer@example.com",
  "u-learner": "learner@example.com"
};

const logEl = document.querySelector("#log");
const statusEl = document.querySelector("#status");
const userSelect = document.querySelector("#userSelect");

function log(message, payload) {
  const line = payload ? `${message}\n${JSON.stringify(payload, null, 2)}` : message;
  logEl.textContent = `${line}\n\n${logEl.textContent}`;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(state.sessionToken ? { authorization: `Bearer ${state.sessionToken}` } : {}),
      ...(options.headers || {})
    }
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error?.message || "Request failed");
  }
  return body;
}

async function login(userId) {
  state.userId = userId;
  const password = document.querySelector("#passwordInput").value;
  if (!password) throw new Error("Password required");
  const response = await fetch("/api/session", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: demoEmails[userId], password })
  });
  const body = await response.json();
  if (!response.ok) throw new Error(body.error?.message || "Login failed");
  state.sessionToken = body.token;
  userSelect.value = userId;
  return body.user;
}

function row(title, meta, html = "") {
  const div = document.createElement("div");
  div.className = "row";
  div.innerHTML = `<strong></strong><div class="meta"></div>${html}`;
  div.querySelector("strong").textContent = title;
  div.querySelector(".meta").textContent = meta;
  return div;
}

async function bootstrap() {
  const data = await api("/api/bootstrap");
  userSelect.innerHTML = "";
  for (const [userId, email] of Object.entries(demoEmails)) {
    const option = document.createElement("option");
    option.value = userId;
    option.textContent = `${email} (${userId})`;
    userSelect.append(option);
  }
  userSelect.value = state.userId;
  statusEl.textContent = `${data.organizations[0].name} · ${data.projects[0].name}`;
}

async function ingest() {
  await login("u-contributor");
  const code = document.querySelector("#codeInput").value;
  const conversation = document.querySelector("#conversationInput").value;
  const manual = await api("/api/ingest/manual", {
    method: "POST",
    body: JSON.stringify({
      organizationId: state.organizationId,
      teamId: state.teamId,
      projectId: state.projectId,
      title: "API timeout code",
      evidenceType: "code",
      content: code,
      provenance: { repo: "demo/repo", filePath: "src/client.js" }
    })
  });
  const imported = await api("/api/ingest/codex-obsidian", {
    method: "POST",
    body: JSON.stringify({
      organizationId: state.organizationId,
      teamId: state.teamId,
      projectId: state.projectId,
      exportData: {
        schemaVersion: 1,
        title: "Codex session",
        conversations: [
          {
            title: "Timeout implementation",
            messages: [
              { role: "user", content: conversation },
              { role: "assistant", content: "Preserve the API boundary and make timeout behavior explicit." }
            ]
          }
        ]
      }
    })
  });
  state.codeBundleId = manual.bundle.id;
  state.conversationBundleId = imported.bundle.id;
  log("Uploaded evidence", { codeBundleId: state.codeBundleId, conversationBundleId: state.conversationBundleId });
}

async function linkSources() {
  await login("u-contributor");
  const result = await api("/api/source-links/suggest", {
    method: "POST",
    body: JSON.stringify({
      conversationBundleId: state.conversationBundleId,
      codeBundleId: state.codeBundleId
    })
  });
  state.sourceLinkId = result.links[0].id;
  await api(`/api/source-links/${state.sourceLinkId}/confirm`, { method: "POST", body: "{}" });
  log("Confirmed source link", { sourceLinkId: state.sourceLinkId });
}

async function generateDraft() {
  await login("u-contributor");
  const result = await api("/api/generation/run", {
    method: "POST",
    body: JSON.stringify({
      organizationId: state.organizationId,
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [state.sourceLinkId],
      visibility: "organization"
    })
  });
  state.reviewTaskId = result.reviewTask.id;
  state.patternCardId = result.patternCard.id;
  log("Generated draft", { patternCardId: state.patternCardId, reviewTaskId: state.reviewTaskId });
}

async function loadReview() {
  await login("u-reviewer");
  const result = await api(`/api/review/tasks?organizationId=${state.organizationId}`);
  const list = document.querySelector("#reviewList");
  list.innerHTML = "";
  for (const task of result.reviewTasks) {
    if (!state.reviewTaskId) state.reviewTaskId = task.id;
    list.append(row(task.patternCard.title, `${task.status} · ${task.patternCard.reviewStatus}`));
  }
  log("Loaded review queue", { count: result.reviewTasks.length });
}

async function approveSelected() {
  await login("u-reviewer");
  const result = await api(`/api/review/tasks/${state.reviewTaskId}/decision`, {
    method: "POST",
    body: JSON.stringify({ decision: "approve", comment: "Reviewed for demo publication." })
  });
  log("Approved asset", result.patternCard);
}

async function loadLibrary() {
  await login("u-learner");
  const result = await api(`/api/library?organizationId=${state.organizationId}`);
  const list = document.querySelector("#libraryList");
  list.innerHTML = "";
  for (const card of result.cards) {
    const tags = card.tags.map((tag) => `<span class="tag">${tag.name}</span>`).join("");
    const item = row(card.title, `${card.publicationStatus} · ${card.problemCount} problems`, tags);
    item.addEventListener("click", () => openCard(card.id));
    list.append(item);
    if (!state.patternCardId) state.patternCardId = card.id;
  }
  log("Loaded library", { count: result.cards.length });
}

async function openCard(cardId) {
  await login("u-learner");
  const result = await api(`/api/pattern-cards/${cardId}`);
  const pane = document.querySelector("#problemPane");
  pane.innerHTML = "";
  const card = result.patternCard;
  pane.append(row(card.title, card.summary));
  for (const problem of card.problems) {
    state.problemId = state.problemId || problem.id;
    const wrapper = row(`${problem.problemType} · ${problem.difficulty}`, problem.prompt);
    const textarea = document.createElement("textarea");
    textarea.value = "This pattern is useful when a team wants reusable implementation guidance without exposing product-specific code.";
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = "Submit";
    button.addEventListener("click", () => submitProblem(problem.id, textarea.value));
    wrapper.append(textarea, button);
    pane.append(wrapper);
  }
}

async function submitProblem(problemId, answer) {
  const result = await api(`/api/problems/${problemId}/submissions`, {
    method: "POST",
    body: JSON.stringify({ textAnswer: answer, resultStatus: "self_marked_complete" })
  });
  log("Submitted answer", { submissionId: result.submission.id });
  await openCard(result.patternCard.id);
}

async function recommendations() {
  await login("u-learner");
  const result = await api(`/api/recommendations?organizationId=${state.organizationId}`);
  log("Recommendations", result.cards.map((card) => card.title));
}

userSelect.addEventListener("change", () => {
  login(userSelect.value).catch((error) => log(error.message));
});

document.querySelector("#ingestBtn").addEventListener("click", () => ingest().catch((error) => log(error.message)));
document.querySelector("#linkBtn").addEventListener("click", () => linkSources().catch((error) => log(error.message)));
document.querySelector("#generateBtn").addEventListener("click", () => generateDraft().catch((error) => log(error.message)));
document.querySelector("#loadReviewBtn").addEventListener("click", () => loadReview().catch((error) => log(error.message)));
document.querySelector("#approveBtn").addEventListener("click", () => approveSelected().catch((error) => log(error.message)));
document.querySelector("#loadLibraryBtn").addEventListener("click", () => loadLibrary().catch((error) => log(error.message)));
document.querySelector("#recommendBtn").addEventListener("click", () => recommendations().catch((error) => log(error.message)));

bootstrap().catch((error) => {
  statusEl.textContent = "Failed to load";
  statusEl.className = "error";
  log(error.message);
});
