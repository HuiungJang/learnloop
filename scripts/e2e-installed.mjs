import assert from "node:assert/strict";
import { existsSync } from "node:fs";
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
const demoProblemId = "problem-demo-practice-workbench";

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
  await page.goBack();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);
  assert.equal(await page.getByRole("button", { name: /^Login$/i }).count(), 0);
  await page.getByRole("button", { name: /AI setup/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.getByRole("button", { name: /^OAuth$/i }).click();
  await page.getByText(/^codex login$/i).waitFor();
  await page.getByRole("radio", { name: /Claude/i }).click();
  assert.equal(await page.getByRole("button", { name: /^OAuth$/i }).isDisabled(), true);
  await page.getByLabel("API key").fill(localAiKey);
  await page.getByRole("button", { name: /Save local setup/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();

  const storedApiKeySettings = await page.evaluate((needle) => {
    const entry = Object.entries(window.localStorage).find(([key, value]) => key.startsWith("learnloop:local-ai:") && value.includes(needle));
    return entry ? JSON.parse(entry[1]) : null;
  }, localAiKey);
  assert.equal(storedApiKeySettings.provider, "claude");
  assert.equal(storedApiKeySettings.authMethod, "api_key");
  assert.equal(storedApiKeySettings.apiKey, localAiKey);

  await page.getByRole("button", { name: /Run flow/i }).click();
  await page.getByText(/Card draft|Card published|Curation open/i).waitFor({ timeout: 20_000 });

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

  await page.getByRole("button", { name: /claude/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.reload({ waitUntil: "networkidle" });
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("button", { name: /^Login$/i }).count(), 0);
  await page.getByRole("button", { name: /claude/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.goBack();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);

  await page.getByRole("button", { name: /claude/i }).click();
  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.getByRole("button", { name: /Back to dashboard/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes curated practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);

  await page.getByRole("button", { name: /claude/i }).click();
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

  await page.locator("#review").getByRole("heading", { name: /Practice Library/i }).waitFor();
  await page.getByText(/Normalize AI-generated tag labels/i).waitFor({ timeout: 20_000 });
  const [practiceResponse] = await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/problems/") && response.url().includes("/practice") && response.status() === 200),
    page.getByRole("button", { name: /Open practice/i }).first().click()
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

  const apiKeyLeakedToServer = postedRequests.some((request) => request.postData.includes(localAiKey));
  const oauthLabelLeakedToServer = postedRequests.some((request) => request.postData.includes(oauthLabel));
  assert.equal(apiKeyLeakedToServer, false);
  assert.equal(oauthLabelLeakedToServer, false);

  console.log(JSON.stringify({
    appUrl,
    email,
    postRequestCount: postedRequests.length,
    localStorageProvider: storedOauthSettings.provider,
    localStorageAuthMethod: storedOauthSettings.authMethod,
    registrationDisabled: true,
    apiKeyLeakedToServer,
    oauthLabelLeakedToServer
  }, null, 2));
} finally {
  await browser.close();
}

function assertLocalAppUrl(value, overrideEnv) {
  const url = new URL(value);
  const localHosts = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
  if (!localHosts.has(url.hostname) && process.env[overrideEnv] !== "1") {
    throw new Error(`${overrideEnv}=1 is required before sending E2E credentials to non-local APP_URL: ${value}`);
  }
}

async function apiRequest(path, options = {}) {
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
  if (!response.ok) {
    throw new Error(`API ${options.method ?? "GET"} ${path} failed with ${response.status}: ${JSON.stringify(payload)}`);
  }
  return payload;
}
