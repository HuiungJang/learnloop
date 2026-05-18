import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const appUrl = process.env.APP_URL ?? "http://localhost:8080";
const sampleCount = Number(process.env.PERF_SAMPLES ?? "20");
const createCards = Number(process.env.PERF_CREATE_CARDS ?? "0");
assertLocalAppUrl(appUrl, "ALLOW_REMOTE_PERF_TARGET");
const env = readEnv();
const password = process.env.APP_DEMO_PASSWORD ?? env.APP_DEMO_PASSWORD;

if (!password) {
  throw new Error("APP_DEMO_PASSWORD was not found in environment or .env");
}

const contributor = await login("contributor@example.com");
const reviewer = await login("reviewer@example.com");
const learner = await login("learner@example.com");

const created = [];
const workflowTimes = [];
for (let index = 0; index < createCards; index += 1) {
  const result = await measure(() => createPublished(index, contributor.token, reviewer.token));
  created.push(result.value);
  workflowTimes.push(result.ms);
}

const libraryTimes = [];
let libraryCardCount = 0;
let lastCardId = created.at(-1);
for (let index = 0; index < sampleCount; index += 1) {
  const result = await api("/api/library?organizationId=org-demo", { token: learner.token });
  libraryTimes.push(result.ms);
  libraryCardCount = result.json.cards.length;
  lastCardId = lastCardId ?? result.json.cards[0]?.id;
}

const detailTimes = [];
if (lastCardId) {
  for (let index = 0; index < sampleCount; index += 1) {
    const result = await api(`/api/pattern-cards/${lastCardId}`, { token: learner.token });
    detailTimes.push(result.ms);
  }
}

const assets = assetSizes();
const report = {
  appUrl,
  sampleCount,
  createdCards: created.length,
  libraryCardCount,
  workflowMedianMs: round(median(workflowTimes)),
  libraryMedianMs: round(median(libraryTimes)),
  detailMedianMs: round(median(detailTimes)),
  frontendJsBytes: assets.jsBytes,
  frontendCssBytes: assets.cssBytes,
  frontendTotalBytes: assets.totalBytes
};

console.log(JSON.stringify(report, null, 2));

async function login(email) {
  return (await api("/api/session", { method: "POST", body: { email, password } })).json;
}

async function createPublished(index, contributorToken, reviewerToken) {
  const common = { organizationId: "org-demo", teamId: "team-platform", projectId: "project-learning" };
  const suffix = `${Date.now()}-${index}`;
  const code = await api("/api/ingest/manual", {
    token: contributorToken,
    method: "POST",
    body: {
      ...common,
      title: `Perf code ${suffix}`,
      sourceKind: "code",
      content: `function retryTimeout${index}(queryClient) { return queryClient.invalidateQueries({ queryKey: ['timeout', '${suffix}'] }); }`
    }
  });
  const conversation = await api("/api/ingest/codex-obsidian", {
    token: contributorToken,
    method: "POST",
    body: {
      ...common,
      exportData: {
        schemaVersion: 1,
        title: `Perf conversation ${suffix}`,
        conversations: [
          {
            messages: [
              { role: "user", content: "Create retry timeout React Query pattern." },
              { role: "assistant", content: "Use explicit query keys and timeout handling." }
            ]
          }
        ]
      }
    }
  });
  const links = await api("/api/source-links/suggest", {
    token: contributorToken,
    method: "POST",
    body: {
      conversationBundleId: conversation.json.bundle.id,
      codeBundleId: code.json.bundle.id
    }
  });
  const linkId = links.json.links[0].id;
  await api(`/api/source-links/${linkId}/confirm`, { token: contributorToken, method: "POST", body: {} });
  const generated = await api("/api/generation/run", {
    token: contributorToken,
    method: "POST",
    body: {
      organizationId: "org-demo",
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [linkId],
      visibility: "organization"
    }
  });
  await api(`/api/review/tasks/${generated.json.reviewTask.id}/decision`, {
    token: reviewerToken,
    method: "POST",
    body: { decision: "approve", comment: "Performance measurement." }
  });
  return generated.json.patternCard.id;
}

async function api(route, { token, method = "GET", body } = {}) {
  const started = performance.now();
  const response = await fetch(`${appUrl}${route}`, {
    method,
    headers: {
      accept: "application/json",
      ...(body === undefined ? {} : { "content-type": "application/json" }),
      ...(token ? { authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = await response.json();
  if (!response.ok) {
    throw new Error(`${method} ${route} ${response.status}: ${JSON.stringify(json)}`);
  }
  return { json, ms: performance.now() - started };
}

async function measure(fn) {
  const started = performance.now();
  const value = await fn();
  return { value, ms: performance.now() - started };
}

function median(values) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

function round(value) {
  return value === null ? null : Number(value.toFixed(2));
}

function assetSizes() {
  const assetsDir = path.join("frontend", "dist", "assets");
  try {
    return readdirSync(assetsDir).reduce(
      (sizes, fileName) => {
        const size = statSync(path.join(assetsDir, fileName)).size;
        if (fileName.endsWith(".js")) sizes.jsBytes += size;
        if (fileName.endsWith(".css")) sizes.cssBytes += size;
        sizes.totalBytes += size;
        return sizes;
      },
      { jsBytes: 0, cssBytes: 0, totalBytes: 0 }
    );
  } catch {
    return { jsBytes: 0, cssBytes: 0, totalBytes: 0 };
  }
}

function readEnv() {
  try {
    return Object.fromEntries(
      readFileSync(".env", "utf8")
        .split(/\n/)
        .filter(Boolean)
        .filter((line) => !line.startsWith("#"))
        .map((line) => {
          const index = line.indexOf("=");
          return [line.slice(0, index), line.slice(index + 1)];
        })
    );
  } catch {
    return {};
  }
}

function assertLocalAppUrl(value, overrideEnv) {
  const url = new URL(value);
  const localHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (!localHosts.has(url.hostname) && process.env[overrideEnv] !== "1") {
    throw new Error(`${overrideEnv}=1 is required before sending performance-test credentials to non-local APP_URL: ${value}`);
  }
}
