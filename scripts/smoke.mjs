import { once } from "node:events";
import { rm } from "node:fs/promises";
import http from "node:http";
import { createApp } from "../src/app.js";
import { JsonStore } from "../src/store.js";

const dataDir = process.env.APP_DATA_DIR || ".local-data-smoke";
const port = Number(process.env.APP_PORT || 4183);
const previousAllowInsecureProviderBaseUrl = process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = "1";
if (dataDir === ".local-data-smoke") {
  await rm(dataDir, { recursive: true, force: true });
}
const fakeProviderOutput = {
  title: "Smoke Provider Generated Pattern",
  summary: "A provider-generated learning pattern for wrapping service calls with reusable timeout and retry behavior.",
  tags: [
    { tagType: "language", name: "TypeScript" },
    { tagType: "pattern", name: "Retry/Timeout" }
  ],
  problems: [
    {
      type: "qa",
      prompt: "When is a bounded timeout pattern appropriate around an API boundary?",
      referenceAnswer: "Use it when callers need predictable failure behavior around transient network or service delays.",
      difficulty: "beginner"
    }
  ]
};
const fakeProviderRequests = [];
const fakeProviderServer = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  fakeProviderRequests.push({
    method: req.method,
    url: req.url,
    headers: req.headers,
    body: Buffer.concat(chunks).toString("utf8")
  });
  res.writeHead(req.url === "/v1/responses" ? 200 : 404, { "content-type": "application/json" });
  res.end(`${JSON.stringify({ output_text: JSON.stringify(fakeProviderOutput), usage: { input_tokens: 180, output_tokens: 220 } })}\n`);
});
fakeProviderServer.listen(0, "127.0.0.1");
await once(fakeProviderServer, "listening");
const fakeProviderAddress = fakeProviderServer.address();
const fakeProviderBaseUrl = `http://127.0.0.1:${fakeProviderAddress.port}`;
const store = new JsonStore(dataDir);
await store.init();
const server = await createApp(store);
server.listen(port, "127.0.0.1");
await once(server, "listening");

const baseUrl = `http://127.0.0.1:${port}`;
const tokenCache = new Map();
const userEmails = {
  "u-admin": "admin@example.com",
  "u-contributor": "contributor@example.com",
  "u-reviewer": "reviewer@example.com",
  "u-learner": "learner@example.com"
};

async function api(path, { userId = "u-admin", method = "GET", body } = {}) {
  let token = tokenCache.get(userId);
  if (!token) {
    const sessionResponse = await fetch(`${baseUrl}/api/session`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email: userEmails[userId], password: "demo-password" })
    });
    const session = await sessionResponse.json();
    if (!sessionResponse.ok) throw new Error(session.error?.message || "Login failed");
    token = session.token;
    tokenCache.set(userId, token);
  }
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = await response.json();
  if (!response.ok) {
    throw new Error(`${method} ${path} failed: ${json.error?.message}`);
  }
  return json;
}

try {
  const common = { organizationId: "org-demo", teamId: "team-platform", projectId: "project-learning" };
  await api("/api/health");
  const code = await api("/api/ingest/manual", {
    userId: "u-contributor",
    method: "POST",
    body: { ...common, title: "Smoke code", content: "async function client() { return fetch('/orders'); }" }
  });
  const convo = await api("/api/ingest/codex-obsidian", {
    userId: "u-contributor",
    method: "POST",
    body: {
      ...common,
      exportData: {
        schemaVersion: 1,
        conversations: [{ title: "Smoke", messages: [{ role: "user", content: "Create a fetch wrapper pattern." }] }]
      }
    }
  });
  const suggested = await api("/api/source-links/suggest", {
    userId: "u-contributor",
    method: "POST",
    body: { conversationBundleId: convo.bundle.id, codeBundleId: code.bundle.id }
  });
  const linkId = suggested.links[0].id;
  await api(`/api/source-links/${linkId}/confirm`, { userId: "u-contributor", method: "POST", body: {} });
  const generated = await api("/api/generation/run", {
    userId: "u-contributor",
    method: "POST",
    body: {
      organizationId: "org-demo",
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [linkId],
      visibility: "organization"
    }
  });
  await api(`/api/review/tasks/${generated.reviewTask.id}/decision`, {
    userId: "u-reviewer",
    method: "POST",
    body: { decision: "approve", comment: "Smoke approved." }
  });
  const library = await api("/api/library?organizationId=org-demo", { userId: "u-learner" });
  const card = await api(`/api/pattern-cards/${library.cards[0].id}`, { userId: "u-learner" });
  await api(`/api/problems/${card.patternCard.problems[0].id}/submissions`, {
    userId: "u-learner",
    method: "POST",
    body: { textAnswer: "Smoke answer", resultStatus: "self_marked_complete" }
  });
  const progress = await api("/api/progress?organizationId=org-demo", { userId: "u-learner" });
  const provider = await api("/api/providers", {
    userId: "u-admin",
    method: "POST",
    body: {
      organizationId: "org-demo",
      provider: "openai",
      model: "fake-responses-model",
      scope: "organization",
      credential: "smoke-provider-secret",
      orgApproved: true,
      baseUrl: fakeProviderBaseUrl
    }
  });
  const providerGenerated = await api("/api/generation/run", {
    userId: "u-contributor",
    method: "POST",
    body: {
      organizationId: "org-demo",
      providerConfigId: provider.provider.id,
      sourceLinkIds: [linkId],
      visibility: "organization"
    }
  });
  if (providerGenerated.patternCard.title !== fakeProviderOutput.title) {
    throw new Error("Provider generation did not use fake provider output");
  }
  if (fakeProviderRequests.length !== 1) {
    throw new Error("Expected exactly one fake provider request");
  }
  console.log(
    JSON.stringify(
      {
        ok: true,
        publishedCards: library.cards.length,
        proficiency: progress.proficiency.length,
        providerDraftTitle: providerGenerated.patternCard.title,
        providerRequests: fakeProviderRequests.length
      },
      null,
      2
    )
  );
} finally {
  await new Promise((resolve) => server.close(resolve));
  await new Promise((resolve) => fakeProviderServer.close(resolve));
  if (previousAllowInsecureProviderBaseUrl === undefined) {
    delete process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
  } else {
    process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = previousAllowInsecureProviderBaseUrl;
  }
}
