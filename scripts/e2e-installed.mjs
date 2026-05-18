import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const appUrl = process.env.APP_URL ?? "http://localhost:8080";
const chromePath = process.env.PLAYWRIGHT_CHROME_PATH ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const launchOptions = existsSync(chromePath) ? { executablePath: chromePath } : {};
assertLocalAppUrl(appUrl, "ALLOW_REMOTE_E2E_TARGET");

const email = `e2e-${Date.now()}@example.com`;
const password = "LocalSecret1234!";
const displayName = "E2E Local User";
const secondEmail = `e2e-second-${Date.now()}@example.com`;
const secondDisplayName = "E2E Second User";
const localAiKey = `local-only-key-${Date.now()}`;
const oauthLabel = `Gemini OAuth ${Date.now()}`;
const postedRequests = [];
const demoProblemId = "problem-demo-practice-workbench";

const browser = await chromium.launch({ headless: true, ...launchOptions });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

page.on("request", (request) => {
  const postData = request.postData();
  if (postData) {
    postedRequests.push({ url: request.url(), postData });
  }
});

try {
  await page.goto(appUrl, { waitUntil: "networkidle" });
  await page.getByRole("button", { name: /Sign up/i }).click();
  await page.getByLabel("Display name").fill(displayName);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/register") && response.status() === 201),
    page.getByRole("button", { name: /Create account/i }).click()
  ]);

  await page.getByRole("heading", { name: /Choose your coding assistant/i }).waitFor();
  await page.getByRole("radio", { name: /Claude/i }).click();
  assert.equal(await page.getByRole("button", { name: /^OAuth$/i }).isDisabled(), true);
  await page.getByLabel("API key").fill(localAiKey);
  await page.getByRole("button", { name: /Save local setup/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes reviewed practice/i }).waitFor();

  const storedApiKeySettings = await page.evaluate((needle) => {
    const entry = Object.entries(window.localStorage).find(([key, value]) => key.startsWith("learnloop:local-ai:") && value.includes(needle));
    return entry ? JSON.parse(entry[1]) : null;
  }, localAiKey);
  assert.equal(storedApiKeySettings.provider, "claude");
  assert.equal(storedApiKeySettings.authMethod, "api_key");
  assert.equal(storedApiKeySettings.apiKey, localAiKey);

  await page.getByRole("button", { name: /Run flow/i }).click();
  await page.getByText(/Card draft|Card published|Review pending/i).waitFor({ timeout: 20_000 });

  await page.getByRole("button", { name: /Logout/i }).click();
  await page.getByRole("button", { name: /^Login$/i }).first().click();
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/session") && response.status() === 201),
    page.getByRole("button", { name: /^Login$/i }).last().click()
  ]);
  await page.getByRole("heading", { name: /Generated code becomes reviewed practice/i }).waitFor();
  assert.equal(await page.getByRole("heading", { name: /Choose your coding assistant/i }).count(), 0);

  await page.getByRole("button", { name: /claude/i }).click();
  await page.getByRole("radio", { name: /Gemini/i }).click();
  await page.getByRole("button", { name: /^OAuth$/i }).click();
  await page.getByLabel("Local OAuth profile").fill(oauthLabel);
  await page.getByRole("button", { name: /Save local setup/i }).click();
  await page.getByRole("heading", { name: /Generated code becomes reviewed practice/i }).waitFor();

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
  await page.locator(".code-editor-root").click();
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
  assert.ok(["passed", "runner_unavailable"].includes(runPayload.run.status));
  if (runPayload.run.status === "runner_unavailable") {
    await page.getByText(/Runner unavailable|Local runner is unavailable/i).first().waitFor({ timeout: 20_000 });
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

  const firstSession = await apiRequest("/api/session", {
    method: "POST",
    body: { email, password }
  });
  const secondSession = await apiRequest("/api/register", {
    method: "POST",
    body: { email: secondEmail, displayName: secondDisplayName, password }
  });
  const firstPractice = await apiRequest(`/api/problems/${demoProblemId}/practice`, { token: firstSession.token });
  const secondPractice = await apiRequest(`/api/problems/${demoProblemId}/practice`, { token: secondSession.token });
  const canonicalContent = firstPractice.problem.files[0].content;
  assert.equal(secondPractice.problem.files[0].content, canonicalContent);

  const firstAttemptsBefore = await apiRequest(`/api/problems/${demoProblemId}/attempts/me`, { token: firstSession.token });
  const secondAttemptsBefore = await apiRequest(`/api/problems/${demoProblemId}/attempts/me`, { token: secondSession.token });
  assert.ok(firstAttemptsBefore.attempts.some((attempt) => attempt.files.some((file) => file.content.includes("toLowerCase()"))));
  assert.equal(secondAttemptsBefore.attempts.length, 0);

  const secondSubmissionContent = "export function formatTag(input: string): string { return `second-${input}` }";
  await apiRequest(`/api/problems/${demoProblemId}/submissions`, {
    method: "POST",
    token: secondSession.token,
    body: {
      clientAttemptId: `second-attempt-${Date.now()}`,
      assetRevision: secondPractice.problem.assetRevision,
      language: "typescript",
      files: [{ path: "src/formatTag.ts", content: secondSubmissionContent }],
      resultStatus: "submitted"
    }
  });

  const firstAttemptsAfter = await apiRequest(`/api/problems/${demoProblemId}/attempts/me`, { token: firstSession.token });
  const secondAttemptsAfter = await apiRequest(`/api/problems/${demoProblemId}/attempts/me`, { token: secondSession.token });
  const firstPracticeAfter = await apiRequest(`/api/problems/${demoProblemId}/practice`, { token: firstSession.token });
  assert.equal(firstPracticeAfter.problem.files[0].content, canonicalContent);
  assert.equal(firstAttemptsAfter.attempts.some((attempt) => attempt.files.some((file) => file.content.includes("second-"))), false);
  assert.ok(secondAttemptsAfter.attempts.some((attempt) => attempt.files.some((file) => file.content.includes("second-"))));

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
    multiUserIsolation: true,
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
