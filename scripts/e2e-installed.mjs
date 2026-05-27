import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { createServer } from "node:http";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const appUrl = process.env.APP_URL ?? "http://localhost:8080";
const chromePath = process.env.PLAYWRIGHT_CHROME_PATH ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const launchOptions = existsSync(chromePath) ? { executablePath: chromePath } : {};
assertLocalAppUrl(appUrl, "ALLOW_REMOTE_E2E_TARGET");

const email = process.env.E2E_OWNER_EMAIL ?? "owner@local.learnloop";
const password = process.env.E2E_OWNER_PASSWORD ?? "demo-password";
const localAiKey = `local-only-key-${Date.now()}`;
const oauthLabel = `Gemini OAuth ${Date.now()}`;
const companionToken = `local-token-${Date.now()}`;
const postedRequests = [];
const companionRequests = [];
let companionMode = "healthy";
let offlineAuthTokenRequests = 0;
const demoProblemId = "problem-demo-practice-workbench";
const fakeProvider = await startFakeOpenAiProvider();
const fakeProviderBackendBaseUrl = process.env.E2E_FAKE_PROVIDER_BASE_URL ?? defaultFakeProviderBackendBaseUrl(fakeProvider.port);

const browser = await chromium.launch({ headless: true, ...launchOptions });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

page.on("request", (request) => {
  const postData = request.postData();
  if (postData) {
    postedRequests.push({ url: request.url(), postData });
  }
  if (request.url().startsWith("http://127.0.0.1:4317/")) {
    companionRequests.push({ method: request.method(), url: request.url(), phase: "request" });
  }
});
page.on("requestfailed", (request) => {
  if (request.url().startsWith("http://127.0.0.1:4317/")) {
    companionRequests.push({ method: request.method(), url: request.url(), phase: "failed", failure: request.failure()?.errorText ?? "unknown" });
  }
});
await page.route("http://127.0.0.1:4317/**", async (route) => {
  const url = new URL(route.request().url());
  companionRequests.push({ method: route.request().method(), url: route.request().url(), phase: "route" });
  const corsHeaders = {
    "access-control-allow-origin": appUrl,
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-learnloop-local-token,authorization",
    "access-control-allow-private-network": "true"
  };
  if (route.request().method() === "OPTIONS") {
    await route.fulfill({ status: 204, headers: corsHeaders });
    return;
  }
  const localCompanionPaths = new Set(["/health", "/providers/status", "/auth/token", "/oauth/start", "/oauth/status"]);
  if (companionMode === "offline" && localCompanionPaths.has(url.pathname)) {
    if (url.pathname === "/auth/token") {
      offlineAuthTokenRequests += 1;
    }
    await route.abort("failed");
    return;
  }
  if (url.pathname === "/health") {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({ status: "ok" })
    });
    return;
  }
  if (url.pathname === "/providers/status") {
    const provider = url.searchParams.get("provider") ?? "codex";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({
        status: "idle",
        provider,
        credentialLabel: provider === "gemini" ? "Google OAuth" : "Codex CLI OAuth",
        message: ""
      })
    });
    return;
  }
  if (url.pathname === "/auth/token") {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({ status: "ok", token: companionToken })
    });
    return;
  }
  if (url.pathname === "/oauth/start") {
    assert.equal(route.request().headers()["x-learnloop-local-token"], companionToken);
    await route.fulfill({
      status: 202,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({
        status: "connected",
        provider: "gemini",
        credentialLabel: oauthLabel,
        message: `${oauthLabel} connected.`
      })
    });
    return;
  }
  if (url.pathname === "/oauth/status") {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({
        status: "connected",
        provider: "gemini",
        credentialLabel: oauthLabel,
        message: `${oauthLabel} connected.`
      })
    });
    return;
  }
  if (url.pathname === "/watchers/status") {
    assert.equal(route.request().headers()["x-learnloop-local-token"], companionToken);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({
        status: "ok",
        collectionEnabled: true,
        uploadQueue: { queued: 0, discardedCount: 0, nextAttemptAt: null },
        watcherCounts: { active: 0, stopped: 0, degraded: 0, unavailable: 0 },
        watchers: []
      })
    });
    return;
  }
  if (url.pathname === "/adapters/status") {
    assert.equal(route.request().headers()["x-learnloop-local-token"], companionToken);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({ status: "ok", adapters: [] })
    });
    return;
  }
  if (url.pathname === "/host/directory-picker") {
    assert.equal(route.request().headers()["x-learnloop-local-token"], companionToken);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: corsHeaders,
      body: JSON.stringify({ status: "ok", path: "/tmp/learnloop-e2e-repository" })
    });
    return;
  }
  await route.fulfill({ status: 404, contentType: "application/json", body: "{}" });
});

try {
  await page.goto(appUrl, { waitUntil: "networkidle" });
  assert.equal(await page.getByRole("button", { name: /Sign up/i }).count(), 0);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/session") && response.status() === 201),
    page.getByRole("button", { name: /^Login$/i }).click()
  ]);

  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.reload({ waitUntil: "networkidle" });
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  assert.equal(await page.getByRole("button", { name: /^Login$/i }).count(), 0);
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);
  assert.equal(await page.getByRole("button", { name: /^Login$/i }).count(), 0);
  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.getByRole("button", { name: /^OAuth$/i }).click();
  await page.getByText(/^codex login$/i).waitFor();
  await page.getByRole("radio", { name: /Claude/i }).click();
  assert.equal(await page.getByRole("button", { name: /^OAuth$/i }).isDisabled(), true);
  await page.getByRole("radio", { name: /Codex/i }).click();
  await page.getByRole("button", { name: /^API key$/i }).click();
  await page.getByLabel("Base URL").fill(fakeProviderBackendBaseUrl);
  await page.getByLabel("API key").fill(localAiKey);
  const [providerCreateResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/providers") && response.request().method() === "POST" && response.status() === 201),
    page.getByRole("button", { name: /Save local setup/i }).click()
  ]);
  const providerCreateBody = await providerCreateResponse.text();
  assert.equal(providerCreateBody.includes(localAiKey), false);
  const providerCreatePayload = JSON.parse(providerCreateBody);
  const apiProviderConfigId = providerCreatePayload.provider.id;
  assert.equal(providerCreatePayload.provider.provider, "codex");
  await page.getByRole("heading", { name: /Install only the languages you use/i }).waitFor();
  await page.locator(".runner-language-row", { hasText: /TypeScript/ }).getByText(/default/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Kotlin/ }).getByText(/default/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Java/ }).getByText(/default/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Swift/ }).getByText(/optional/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Rust/ }).getByText(/optional/i).waitFor();
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();

  const storedApiKeySettings = await page.evaluate(() => {
    const entry = Object.entries(window.localStorage).find(([key]) => key.startsWith("learnloop:local-ai:"));
    return entry ? JSON.parse(entry[1]) : null;
  });
  const apiKeyInLocalStorage = await page.evaluate((needle) => Object.values(window.localStorage).some((value) => value.includes(needle)), localAiKey);
  assert.equal(apiKeyInLocalStorage, false);
  assert.equal(storedApiKeySettings.provider, "codex");
  assert.equal(storedApiKeySettings.authMethod, "api_key");
  assert.equal(storedApiKeySettings.providerConfigId, apiProviderConfigId);
  assert.equal(Object.prototype.hasOwnProperty.call(storedApiKeySettings, "apiKey"), false);

  const ownerProviderSession = await apiRequest("/api/session", {
    method: "POST",
    body: { email, password }
  });
  const generationCredential = `fake-provider-generation-${Date.now()}`;
  const generationProvider = await apiRequest("/api/providers", {
    method: "POST",
    token: ownerProviderSession.token,
    body: {
      organizationId: "org-demo",
      provider: "codex",
      model: "gpt-5.2",
      baseUrl: fakeProviderBackendBaseUrl,
      scope: "organization",
      credential: generationCredential,
      retentionMode: "standard",
      authType: "byok"
    }
  });
  const generationProviderConfigId = generationProvider.provider.id;
  const providerEvidenceBundleId = await createLocalAiSessionEvidence(ownerProviderSession.token, "provider-success");
  fakeProvider.enqueue({ body: openAiResponseBody(providerPatternOutput("E2E Provider Pattern")) });
  const providerGenerationStartedAt = Date.now();
  const providerGeneration = await apiRequest("/api/generation/run", {
    method: "POST",
    token: ownerProviderSession.token,
    body: {
      organizationId: "org-demo",
      providerConfigId: generationProviderConfigId,
      sourceBundleIds: [providerEvidenceBundleId],
      visibility: "organization",
      idempotencyKey: `e2e-provider-success-${Date.now()}`
    }
  });
  assert.equal(providerGeneration.patternCard.title, "E2E Provider Pattern");
  assert.equal(fakeProvider.awaitRequestCount(1).length, 1);
  assert.equal(fakeProvider.requests[0].method, "POST");
  assert.equal(fakeProvider.requests[0].url, "/v1/responses");
  assert.equal(fakeProvider.requests[0].headers.authorization, `Bearer ${generationCredential}`);
  assert.ok(Date.now() - providerGenerationStartedAt < 10_000);
  await apiRequest(`/api/review/tasks/${providerGeneration.reviewTask.id}/decision`, {
    method: "POST",
    token: ownerProviderSession.token,
    body: { decision: "approve", comment: "E2E provider generation approved." }
  });

  const failureEvidenceBundleId = await createLocalAiSessionEvidence(ownerProviderSession.token, "provider-failure");
  fakeProvider.enqueue({ status: 401, body: JSON.stringify({ error: "do-not-leak-provider-body" }) });
  const failedGeneration = await apiRequestRaw("/api/generation/run", {
    method: "POST",
    token: ownerProviderSession.token,
    body: {
      organizationId: "org-demo",
      providerConfigId: generationProviderConfigId,
      sourceBundleIds: [failureEvidenceBundleId],
      visibility: "organization",
      idempotencyKey: `e2e-provider-failure-${Date.now()}`
    }
  });
  assert.equal(failedGeneration.status, 503);
  assert.equal(failedGeneration.payload.error.fields.failureCode, "provider_http_error");
  assert.equal(fakeProvider.awaitRequestCount(2).length, 2);

  const localMockBundleId = await createLocalAiSessionEvidence(ownerProviderSession.token, "local-mock");
  const requestsBeforeLocalMock = fakeProvider.requests.length;
  await apiRequest("/api/generation/run", {
    method: "POST",
    token: ownerProviderSession.token,
    body: {
      organizationId: "org-demo",
      providerConfigId: "provider-local-mock",
      sourceBundleIds: [localMockBundleId],
      visibility: "organization",
      idempotencyKey: `e2e-local-mock-${Date.now()}`
    }
  });
  assert.equal(fakeProvider.requests.length, requestsBeforeLocalMock);

  await page.getByRole("button", { name: /Practice/i }).click();
  await page.getByRole("heading", { name: /Practice Library/i }).waitFor();
  const providerPracticeCard = page.locator(".practice-list-row", { hasText: /E2E Provider Pattern/i }).first();
  await providerPracticeCard.waitFor({ timeout: 20_000 });
  await providerPracticeCard.click();
  const [providerPracticeResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/problems/") && response.url().includes("/practice") && response.status() === 200),
    page.locator(".problem-action-list button").nth(1).click()
  ]);
  const providerPracticePayload = await providerPracticeResponse.json();
  assert.equal(providerPracticePayload.problem.title, "E2E Provider Pattern");
  await page.getByText(/Implement a validated adapter/i).waitFor({ timeout: 20_000 });
  await page.getByRole("button", { name: /Overview/i }).click();

  await page.getByRole("button", { name: /Run flow/i }).click();
  await page.getByText(/Card draft|Card published|Curation open/i).first().waitFor({ timeout: 20_000 });

  await page.getByRole("button", { name: /Collection/i }).click();
  await page.getByRole("heading", { name: /Collection Status/i }).waitFor();
  await page.getByRole("button", { name: /Select repository folder/i }).click();
  await page.getByRole("button", { name: /learnloop-e2e-repository/i }).waitFor();

  await page.getByRole("button", { name: /Logout/i }).click();
  await page.getByRole("button", { name: /^Login$/i }).first().click();
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/session") && response.status() === 201),
    page.getByRole("button", { name: /^Login$/i }).last().click()
  ]);
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);

  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.reload({ waitUntil: "networkidle" });
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("button", { name: /^Login$/i }).count(), 0);
  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  companionMode = "offline";
  await page.getByRole("radio", { name: /Codex/i }).click();
  await page.getByRole("button", { name: /^OAuth$/i }).click();
  await page.getByText(/Companion offline/i).first().waitFor({ timeout: 10_000 });
  await page.locator("code", { hasText: "./scripts/local-ai-companion.sh start" }).waitFor({ timeout: 10_000 });
  assert.equal(await page.getByRole("button", { name: /Connect Codex/i }).isDisabled(), true);
  assert.equal(offlineAuthTokenRequests, 0);
  companionMode = "healthy";
  await page.getByRole("button", { name: /Refresh companion/i }).click();
  await page.getByText(/Companion online/i).first().waitFor({ timeout: 10_000 });
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);
  await page.getByRole("button", { name: /Runners/i }).click();
  await page.getByRole("heading", { name: /Install only the languages you use/i }).waitFor();
  await page.locator(".runner-language-row", { hasText: /Swift/ }).getByText(/optional/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Rust/ }).getByText(/optional/i).waitFor();
  await page.locator(".runner-language-row", { hasText: /Rust/ }).getByText(/local image|registry image|bundled image/i).waitFor();
  assert.equal(await page.getByText(/pull access denied for learnloop-runner-rust/i).count(), 0);
  await page.getByRole("button", { name: /Refresh/i }).click();
  await page.getByText(/Runner status refreshed|Checking local runner images/i).waitFor({ timeout: 20_000 });
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();

  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);

  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("radio", { name: /Gemini/i }).click();
  await page.getByRole("button", { name: /^OAuth$/i }).click();
  await page.getByRole("button", { name: /Connect Gemini/i }).click();
  try {
    await page.getByText(/^Connected$/i).waitFor({ timeout: 10_000 });
  } catch (error) {
    const status = await page.locator(".oauth-status").innerText().catch(() => "missing");
    const message = await page.locator(".oauth-message").innerText().catch(() => "missing");
    throw new Error(`OAuth connection did not reach connected. status=${status}; message=${message}; companionRequests=${JSON.stringify(companionRequests)}`, { cause: error });
  }
  assert.equal(await page.getByLabel("Local OAuth profile").inputValue(), oauthLabel);
  await page.getByRole("button", { name: /Save local setup/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();

  const storedOauthSettings = await page.evaluate(() => {
    const entry = Object.entries(window.localStorage).find(([key]) => key.startsWith("learnloop:local-ai:"));
    return entry ? JSON.parse(entry[1]) : null;
  });
  assert.equal(storedOauthSettings.provider, "gemini");
  assert.equal(storedOauthSettings.authMethod, "oauth");
  assert.equal(storedOauthSettings.credentialLabel, oauthLabel);
  assert.equal(Object.prototype.hasOwnProperty.call(storedOauthSettings, "apiKey"), false);

  await page.getByRole("button", { name: /Practice/i }).click();
  await page.getByRole("heading", { name: /Practice Library/i }).waitFor();
  await page.getByLabel("Pattern/API").fill("Pure Function");
  const practiceCardItem = page.locator(".practice-list-row", { hasText: /Normalize AI-generated tag labels/i }).first();
  await practiceCardItem.waitFor({ timeout: 20_000 });
  await practiceCardItem.click();
  const practiceProblemButton = page.locator(".problem-action-list button").first();
  await practiceProblemButton.waitFor({ timeout: 20_000 });
  const [practiceResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/problems/") && response.url().includes("/practice") && response.status() === 200),
    practiceProblemButton.click()
  ]);
  const practicePayload = await practiceResponse.text();
  assert.equal(practicePayload.includes("referenceAnswer"), false);
  assert.equal(practicePayload.includes("Trim input, split separators"), false);

  await page.getByRole("heading", { name: /Normalize AI-generated tag labels/i }).waitFor();
  await page.locator(".monaco-editor textarea").waitFor({ timeout: 20_000 });
  await page.locator(".view-line").filter({ hasText: "export function formatTag" }).first().waitFor({ timeout: 20_000 });
  await page.locator(".view-line").filter({ hasText: "export function formatTag" }).first().click();
  const shortcut = process.platform === "darwin" ? "Meta" : "Control";
  await page.keyboard.press(`${shortcut}+A`);
  await page.keyboard.insertText(`
export function formatTag(input: string): string {
  return input
    .trim()
    .toLowerCase()
    .replace(/[_\\s-]+/g, "-")
    .replace(/^-|-$/g, "")
}
`.trimStart());
  const [runResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/problems/") && response.url().includes("/runs") && response.status() === 200),
    page.keyboard.press(`${shortcut}+Shift+Enter`)
  ]);
  const runPayload = await runResponse.json();
  assert.ok(["passed", "failed", "compile_error", "runner_unavailable"].includes(runPayload.run.status), JSON.stringify(runPayload.run));
  if (runPayload.run.status === "runner_unavailable") {
    await page.getByText(/Runner unavailable|Local runner is unavailable/i).first().waitFor({ timeout: 20_000 });
  } else if (runPayload.run.status === "failed" || runPayload.run.status === "compile_error") {
    await page.getByText(/Run failed|Latest run failed|Compile failed|Compilation failed/i).first().waitFor({ timeout: 20_000 });
  } else {
    await page.getByText(/Run passed|Latest run passed/i).first().waitFor({ timeout: 20_000 });
  }

  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/attempts/local-sync") && response.status() === 200),
    page.keyboard.press(`${shortcut}+S`)
  ]);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/submissions") && response.status() === 201),
    page.keyboard.press(`${shortcut}+Enter`)
  ]);
  await page.waitForResponse((response) => response.url().includes("/api/progress") && response.status() === 200);
  await page.getByText(/Submitted/i).waitFor();
  await page.getByText(/Answer diff/i).waitFor({ timeout: 20_000 });
  await page.getByRole("button", { name: /Overview/i }).click();
  await page.waitForFunction(() => !document.body.innerText.includes("No submissions yet."));
  const progressPanel = page.locator(".panel").filter({ has: page.getByRole("heading", { name: /^Progress$/i }) });
  await progressPanel.getByText(/Pure Function/i).waitFor({ timeout: 20_000 });
  await progressPanel.getByText(/TypeScript/i).waitFor({ timeout: 20_000 });

  const ownerSession = await apiRequest("/api/session", {
    method: "POST",
    body: { email, password }
  });

  const disabledRegistration = await fetch(`${appUrl}/api/register`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      email: `blocked-${Date.now()}@example.com`,
      displayName: "Blocked User",
      password: "LocalSecret1234!"
    })
  });
  assert.equal(disabledRegistration.status, 403);

  const firstPractice = await apiRequest(`/api/problems/${demoProblemId}/practice`, { token: ownerSession.token });

  const runnerHealth = await apiRequest("/api/runner/health");
  const directRunnerFiles = firstPractice.problem.files.map((file) => ({
    path: file.path,
    content:
      file.path === "src/formatTag.ts"
        ? `
export function formatTag(input: string): string {
  return input
    .trim()
    .toLowerCase()
    .replace(/[_\\s-]+/g, "-")
    .replace(/^-|-$/g, "")
}
`.trimStart()
        : file.content
  }));
  const directRun = await apiRequest(`/api/problems/${demoProblemId}/runs`, {
    method: "POST",
    token: ownerSession.token,
    body: {
      clientAttemptId: `direct-run-${Date.now()}`,
      assetRevision: firstPractice.problem.assetRevision,
      language: "typescript",
      timeoutMs: 5_000,
      files: directRunnerFiles
    }
  });
  if (runnerHealth.status === "ready") {
    assert.equal(directRun.run.status, "passed", JSON.stringify(directRun.run));
  } else {
    assert.ok(["passed", "runner_unavailable"].includes(directRun.run.status), JSON.stringify(directRun.run));
  }

  const firstAttemptsBefore = await apiRequest(`/api/problems/${demoProblemId}/attempts/me`, { token: ownerSession.token });
  assert.ok(firstAttemptsBefore.attempts.some((attempt) => attempt.files.some((file) => file.content.includes("toLowerCase()"))));

  const apiKeyProviderPosts = postedRequests.filter((request) => request.url.includes("/api/providers") && request.postData.includes(localAiKey));
  const apiKeyLeakedOutsideProviderSave = postedRequests.some((request) => !request.url.includes("/api/providers") && request.postData.includes(localAiKey));
  const oauthLabelLeakedToServer = postedRequests.some((request) => request.postData.includes(oauthLabel));
  assert.equal(apiKeyProviderPosts.length, 1);
  assert.equal(apiKeyLeakedOutsideProviderSave, false);
  assert.equal(oauthLabelLeakedToServer, false);

  console.log(JSON.stringify({
    appUrl,
    email,
    postRequestCount: postedRequests.length,
    fakeProviderRequestCount: fakeProvider.requests.length,
    localStorageProvider: storedOauthSettings.provider,
    localStorageAuthMethod: storedOauthSettings.authMethod,
    registrationDisabled: true,
    apiKeyLeakedOutsideProviderSave,
    oauthLabelLeakedToServer,
    offlineAuthTokenRequests
  }, null, 2));
} finally {
  await browser.close();
  await fakeProvider.close();
}

function assertLocalAppUrl(value, overrideEnv) {
  const url = new URL(value);
  const localHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (!localHosts.has(url.hostname) && process.env[overrideEnv] !== "1") {
    throw new Error(`${overrideEnv}=1 is required before sending E2E credentials to non-local APP_URL: ${value}`);
  }
}

async function apiRequest(path, options = {}) {
  const { payload, status } = await apiRequestRaw(path, options);
  if (status < 200 || status >= 300) {
    throw new Error(`API ${options.method ?? "GET"} ${path} failed with ${status}: ${JSON.stringify(payload)}`);
  }
  return payload;
}

async function apiRequestRaw(path, options = {}) {
  const response = await fetch(`${appUrl}${path}`, {
    method: options.method ?? "GET",
    headers: {
      Accept: "application/json",
      ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(options.token === undefined ? {} : { Authorization: `Bearer ${options.token}` })
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });
  const payload = await response.json();
  return { status: response.status, payload };
}

async function createLocalAiSessionEvidence(token, suffix) {
  const repoIdentityHash = `e2e-provider-repo-${suffix}-${Date.now()}`;
  const repositoryDisplayLabel = `e2e-provider-${suffix}`;
  await apiRequest(`/api/local-repositories/${encodeURIComponent(repoIdentityHash)}`, {
    method: "PATCH",
    token,
    body: {
      organizationId: "org-demo",
      displayLabel: repositoryDisplayLabel,
      status: "approved",
      repoRoot: "/app"
    }
  });
  const created = await apiRequest("/api/ingest/local-ai-session", {
    method: "POST",
    token,
    body: {
      organizationId: "org-demo",
      teamId: "team-platform",
      projectId: "project-learning",
      title: `E2E provider evidence ${suffix}`,
      sourceKind: "local_ai_session",
      repoIdentityHash,
      repositoryDisplayLabel,
      repositoryUrl: "file:///app",
      commitSha: sha256Hex(`commit-${suffix}`).slice(0, 16),
      branchName: `e2e/provider-${suffix}`,
      toolProvider: "codex-cli",
      toolSessionId: `session-${suffix}`,
      toolEventId: `event-${suffix}`,
      timestampBucket: new Date().toISOString().slice(0, 16),
      idempotencyKey: `${repoIdentityHash}:event-${suffix}`,
      autoAttribution: "ai_assisted",
      attributionConfidence: 0.92,
      attributionReasons: ["tool_session", "e2e"],
      artifacts: [
        localSessionArtifact("prompt", null, `Generate a reusable API boundary pattern for ${suffix}.`),
        localSessionArtifact("ai_response", null, `Use validated provider output and no partial assets for ${suffix}.`),
        localSessionArtifact("file_after", "src/provider.ts", `export const providerPattern = "${suffix}";`),
        localSessionArtifact("diff", "src/provider.ts", `+export const providerPattern = "${suffix}";`)
      ]
    }
  });
  await apiRequest(`/api/evidence/${created.bundle.id}/attribution`, {
    method: "PATCH",
    token,
    body: {
      userAttribution: "use_for_generation",
      attributionConfidence: 0.9,
      attributionReasons: ["curation_approved"]
    }
  });
  return created.bundle.id;
}

function localSessionArtifact(itemType, repoRelativePath, content) {
  return {
    itemType,
    repoRelativePath,
    content,
    contentHash: sha256Hex(`${itemType}:${repoRelativePath ?? ""}:${content}`),
    metadata: {}
  };
}

async function startFakeOpenAiProvider() {
  const requests = [];
  const responses = [];
  const server = createServer((request, response) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => {
      const body = Buffer.concat(chunks).toString("utf8");
      requests.push({
        method: request.method,
        url: request.url,
        headers: request.headers,
        body
      });
      const next = responses.shift() ?? { status: 500, body: JSON.stringify({ error: "No fake provider response enqueued" }) };
      response.writeHead(next.status ?? 200, { "content-type": "application/json", ...(next.headers ?? {}) });
      response.end(next.body ?? "{}");
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    port: server.address().port,
    requests,
    enqueue(response) {
      responses.push(response);
    },
    awaitRequestCount(expected, timeoutMs = 2_000) {
      const startedAt = Date.now();
      while (Date.now() - startedAt < timeoutMs) {
        if (requests.length >= expected) return requests;
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10);
      }
      assert.ok(requests.length >= expected, `Expected ${expected} fake provider requests but received ${requests.length}`);
      return requests;
    },
    close() {
      return new Promise((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  };
}

function defaultFakeProviderBackendBaseUrl(port) {
  return process.platform === "darwin" ? `http://host.docker.internal:${port}` : `http://127.0.0.1:${port}`;
}

function openAiResponseBody(outputText) {
  return JSON.stringify({ output_text: outputText });
}

function providerPatternOutput(title) {
  return JSON.stringify({
    patterns: [
      {
        title,
        summary: "Provider generated summary.",
        confidence: 0.91,
        tags: [{ type: "framework", name: "Provider React" }],
        evidenceRefs: ["bundle"],
        languageAgnosticExplanation: "Keep the boundary explicit.",
        implementationGuidance: ["Validate provider output before persistence."],
        commonFailureModes: ["Invalid provider JSON."],
        problems: [
          { type: "qa", difficulty: "easy", prompt: "When is this pattern useful?", referenceAnswer: "When provider output must be validated before use." },
          { type: "short_implementation", difficulty: "medium", prompt: "Implement a validated adapter.", referenceAnswer: "Parse into a strict DTO before persistence." },
          { type: "debugging", difficulty: "medium", prompt: "What should fail safely?", referenceAnswer: "Invalid JSON and HTTP failures should create no assets." }
        ],
        reviewRisks: ["correctness"]
      }
    ]
  });
}

function sha256Hex(value) {
  return createHash("sha256").update(value).digest("hex");
}
