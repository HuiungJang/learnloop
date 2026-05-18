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
const localAiKey = `local-only-key-${Date.now()}`;
const oauthLabel = `Gemini OAuth ${Date.now()}`;
const postedRequests = [];

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
    const entry = Object.entries(window.localStorage).find(([key, value]) => key.startsWith("ai-code-learning:local-ai:") && value.includes(needle));
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
    const entry = Object.entries(window.localStorage).find(([key]) => key.startsWith("ai-code-learning:local-ai:"));
    return entry ? JSON.parse(entry[1]) : null;
  });
  assert.equal(storedOauthSettings.provider, "gemini");
  assert.equal(storedOauthSettings.authMethod, "oauth");
  assert.equal(storedOauthSettings.credentialLabel, oauthLabel);
  assert.equal(Object.prototype.hasOwnProperty.call(storedOauthSettings, "apiKey"), false);

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
