const state = {
  userId: "u-admin",
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
      "x-user-id": state.userId,
      ...(options.headers || {})
    }
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error?.message || "Request failed");
  }
  return body;
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
  for (const user of data.users) {
    const option = document.createElement("option");
    option.value = user.id;
    option.textContent = `${user.displayName} (${user.id})`;
    userSelect.append(option);
  }
  userSelect.value = state.userId;
  statusEl.textContent = `${data.organizations[0].name} · ${data.projects[0].name}`;
}

async function ingest() {
  state.userId = "u-contributor";
  userSelect.value = state.userId;
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
  state.userId = "u-contributor";
  userSelect.value = state.userId;
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
  state.userId = "u-contributor";
  userSelect.value = state.userId;
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
  state.userId = "u-reviewer";
  userSelect.value = state.userId;
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
  state.userId = "u-reviewer";
  userSelect.value = state.userId;
  const result = await api(`/api/review/tasks/${state.reviewTaskId}/decision`, {
    method: "POST",
    body: JSON.stringify({ decision: "approve", comment: "Reviewed for demo publication." })
  });
  log("Approved asset", result.patternCard);
}

async function loadLibrary() {
  state.userId = "u-learner";
  userSelect.value = state.userId;
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
  state.userId = "u-learner";
  userSelect.value = state.userId;
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
  state.userId = "u-learner";
  userSelect.value = state.userId;
  const result = await api(`/api/recommendations?organizationId=${state.organizationId}`);
  log("Recommendations", result.cards.map((card) => card.title));
}

userSelect.addEventListener("change", () => {
  state.userId = userSelect.value;
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
